package com.storyflow.assistant;

import com.storyflow.ai.AiRequestOverride;
import com.storyflow.ai.LlmGateway;
import com.storyflow.common.BusinessException;
import com.storyflow.dto.WorkspaceDtos;
import com.storyflow.entity.Asset;
import com.storyflow.entity.AssistantMessage;
import com.storyflow.entity.AssetType;
import com.storyflow.entity.Project;
import com.storyflow.entity.ScriptEntry;
import com.storyflow.entity.Shot;
import com.storyflow.entity.SourceText;
import com.storyflow.entity.User;
import com.storyflow.repository.AssistantMessageRepository;
import com.storyflow.repository.AssetRepository;
import com.storyflow.repository.ScriptEntryRepository;
import com.storyflow.repository.ShotRepository;
import com.storyflow.repository.SourceTextRepository;
import com.storyflow.service.AiUsageLogService;
import com.storyflow.service.CurrentUserService;
import com.storyflow.service.ProjectService;
import com.storyflow.service.QuotaService;
import com.storyflow.service.WorkspaceService;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * AI 助手独立业务模块：对话、写入原文等动作，与工作台生成流水线解耦。
 */
@Service
public class AssistantService {

    private final ProjectService projectService;
    private final SourceTextRepository sourceTextRepository;
    private final ScriptEntryRepository scriptEntryRepository;
    private final AssetRepository assetRepository;
    private final ShotRepository shotRepository;
    private final AssistantMessageRepository assistantMessageRepository;
    private final LlmGateway llmGateway;
    private final CurrentUserService currentUserService;
    private final AiUsageLogService aiUsageLogService;
    private final WorkspaceService workspaceService;
    private final QuotaService quotaService;
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    public AssistantService(ProjectService projectService,
                            SourceTextRepository sourceTextRepository,
                            ScriptEntryRepository scriptEntryRepository,
                            AssetRepository assetRepository,
                            ShotRepository shotRepository,
                            AssistantMessageRepository assistantMessageRepository,
                            LlmGateway llmGateway,
                            CurrentUserService currentUserService,
                            AiUsageLogService aiUsageLogService,
                            WorkspaceService workspaceService,
                            QuotaService quotaService) {
        this.projectService = projectService;
        this.sourceTextRepository = sourceTextRepository;
        this.scriptEntryRepository = scriptEntryRepository;
        this.assetRepository = assetRepository;
        this.shotRepository = shotRepository;
        this.assistantMessageRepository = assistantMessageRepository;
        this.llmGateway = llmGateway;
        this.currentUserService = currentUserService;
        this.aiUsageLogService = aiUsageLogService;
        this.workspaceService = workspaceService;
        this.quotaService = quotaService;
    }

    @PreDestroy
    public void shutdownStreamExecutor() {
        streamExecutor.shutdown();
        try {
            if (!streamExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                streamExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            streamExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public AssistantDtos.MessageResponse chat(Long projectId, AssistantDtos.ChatRequest req) {
        Project project = projectService.requireWritableProject(projectId);
        quotaService.checkAiUsage(AiUsageLogService.TYPE_ASSISTANT_CHAT);

        if (!Boolean.TRUE.equals(req.getResumeConfirm())) {
            AssistantMessage userMsg = new AssistantMessage();
            userMsg.setProjectId(projectId);
            userMsg.setUserId(currentUserService.getCurrentUserId());
            userMsg.setRole("user");
            userMsg.setContent(req.getMessage());
            assistantMessageRepository.save(userMsg);
        }

        String context = buildContext(projectId, req);
        String message = req.getMessage() == null ? "" : req.getMessage().trim();
        boolean confirmed = Boolean.TRUE.equals(req.getConfirmed());

        String reply;
        boolean sourceUpdated = false;
        boolean shotsUpdated = false;
        boolean scriptsUpdated = false;
        boolean assetsUpdated = false;
        boolean needsConfirm = false;
        String confirmKind = null;
        String confirmSummary = null;
        List<String> applied = new ArrayList<>();
        boolean failed = false;

        try {
            PipelineIntent pipelineIntent = resolvePipelineIntent(message);
            ItemOpResult fine = tryExecuteScriptAssetOps(
                    projectId, message, req.getSelectedShotIds(), req.getFocus(), req.getFocusId(), null, confirmed);
            if (fine != null) {
                if (fine.needsConfirm()) {
                    needsConfirm = true;
                    confirmKind = fine.confirmKind();
                    confirmSummary = fine.confirmSummary();
                }
                scriptsUpdated = fine.scriptsUpdated();
                assetsUpdated = fine.assetsUpdated();
                applied.addAll(fine.applied());
                reply = fine.reply();
            } else if (pipelineIntent == PipelineIntent.GENERATE_SCRIPT) {
                PipelineResult result = executeGenerateScript(projectId, null);
                scriptsUpdated = result.updated();
                applied.addAll(result.applied());
                reply = result.reply();
            } else if (pipelineIntent == PipelineIntent.EXTRACT_ASSETS) {
                PipelineResult result = executeExtractAssets(projectId, null);
                assetsUpdated = result.updated();
                applied.addAll(result.applied());
                reply = result.reply();
            } else {
                ShotsIntent shotsIntent = resolveGenerateShotsIntent(projectId, context, message, null, req.getSelectedShotIds());
                if (shotsIntent.mode() == ShotsIntentMode.ASK_WHICH) {
                    if (isDeleteShotIntent(message)) {
                        reply = "请指定要删除的分镜编号，例如「删除第 1 个分镜」或「删除第 2、3 个」。"
                                + "也可以先勾选分镜再说「删除这些分镜」。";
                    } else {
                        reply = "请指定要生成的分镜编号，例如「生成第 1 个分镜提示词」或「生成第 2、3 个」。"
                                + "若要全部生成，请明确说「生成全部分镜提示词」。";
                    }
                } else if (shotsIntent.needsDelete()) {
                    String summary = summarizeDeleteShots(shotsIntent);
                    if (!confirmed) {
                        needsConfirm = true;
                        confirmKind = "delete_shots";
                        confirmSummary = summary;
                        reply = pendingConfirmReply(summary);
                    } else {
                        GenerateShotsResult result = executeDeleteShotsIntent(projectId, shotsIntent, null);
                        shotsUpdated = result.shotsUpdated();
                        applied.addAll(result.applied());
                        reply = result.reply();
                    }
                } else if (shotsIntent.needsGenerate()) {
                    GenerateShotsResult result = executeGenerateShotsIntent(
                            projectId, shotsIntent, () -> false, null, null, null);
                    shotsUpdated = result.shotsUpdated();
                    applied.addAll(result.applied());
                    reply = result.reply();
                } else {
                    String existing = loadExistingSource(projectId);
                    LlmGateway.WriteSourceIntent intent = resolveWriteSourceIntent(context, message, existing);
                    if (intent.needsSourceWrite()) {
                        ConfirmGate gate = gateDangerousWriteSource(intent, existing, confirmed);
                        if (gate != null) {
                            needsConfirm = true;
                            confirmKind = gate.kind();
                            confirmSummary = gate.summary();
                            reply = pendingConfirmReply(gate.summary());
                        } else {
                            WriteSourceResult result = executeWriteSourceIntent(
                                    projectId, project.getStylePreset(), message, existing, intent,
                                    () -> false, null, null);
                            sourceUpdated = result.sourceUpdated();
                            applied.addAll(result.applied());
                            reply = result.reply();
                        }
                    } else if (intent.mode() == LlmGateway.WriteSourceMode.ALREADY_COMPLETE) {
                        reply = alreadyCompleteReply(existing, intent.prefixTarget());
                    } else {
                        reply = llmGateway.assistantChatReply(context, message);
                    }
                }
            }
        } catch (Exception ex) {
            failed = true;
            reply = "生成失败：" + (ex.getMessage() != null ? ex.getMessage() : "未知错误");
        }

        reply = withChangeNote(reply, applied);

        AssistantMessage assistantMsg = new AssistantMessage();
        assistantMsg.setProjectId(projectId);
        assistantMsg.setUserId(currentUserService.getCurrentUserId());
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(reply);
        assistantMessageRepository.save(assistantMsg);
        logAssistantUsage(projectId, message, reply, failed);

        AssistantDtos.MessageResponse resp = toResponse(assistantMsg);
        resp.setSourceUpdated(sourceUpdated);
        resp.setShotsUpdated(shotsUpdated);
        resp.setScriptsUpdated(scriptsUpdated);
        resp.setAssetsUpdated(assetsUpdated);
        resp.setAppliedActions(applied);
        resp.setNeedsConfirm(needsConfirm);
        resp.setConfirmKind(confirmKind);
        resp.setConfirmSummary(confirmSummary);
        return resp;
    }

    /** SSE 流式对话：token / status / done / error */
    public SseEmitter chatStream(Long projectId, AssistantDtos.ChatRequest req) {
        Project project = projectService.requireWritableProject(projectId);
        quotaService.checkAiUsage(AiUsageLogService.TYPE_ASSISTANT_CHAT);
        SseEmitter emitter = new SseEmitter(600_000L);

        if (!Boolean.TRUE.equals(req.getResumeConfirm())) {
            AssistantMessage userMsg = new AssistantMessage();
            userMsg.setProjectId(projectId);
            userMsg.setUserId(currentUserService.getCurrentUserId());
            userMsg.setRole("user");
            userMsg.setContent(req.getMessage());
            assistantMessageRepository.save(userMsg);
        }

        String context = buildContext(projectId, req);
        String message = req.getMessage() == null ? "" : req.getMessage().trim();
        String style = project.getStylePreset();
        boolean confirmed = Boolean.TRUE.equals(req.getConfirmed());

        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(ex -> cancelled.set(true));
        emitter.onCompletion(() -> cancelled.set(true));

        // SSE 在线程池执行：请求线程返回后 Interceptor 会清掉 ThreadLocal，
        // 必须把用户与个人 AI 覆盖显式带进工作线程，否则落库/用量日志抛「请先登录」、SSE 永不 complete。
        AiRequestOverride.Override aiOverride = AiRequestOverride.get();
        User streamUser = currentUserService.getRequestUser();

        streamExecutor.execute(() -> {
            AiRequestOverride.set(aiOverride);
            if (streamUser != null) {
                currentUserService.setRequestUser(streamUser);
            }
            boolean sourceUpdated = false;
            boolean shotsUpdated = false;
            boolean scriptsUpdated = false;
            boolean assetsUpdated = false;
            boolean needsConfirm = false;
            String confirmKind = null;
            String confirmSummary = null;
            List<String> applied = new ArrayList<>();
            String reply = "";
            boolean stopped = false;
            try {
                BooleanSupplier isCancelled = cancelled::get;
                PipelineIntent pipelineIntent = resolvePipelineIntent(message);
                ItemOpResult fine = tryExecuteScriptAssetOps(
                        projectId,
                        message,
                        req.getSelectedShotIds(),
                        req.getFocus(),
                        req.getFocusId(),
                        status -> sendStreamEvent(emitter, cancelled, "status", Map.of("text", status)),
                        confirmed
                );
                if (fine != null) {
                    if (fine.needsConfirm()) {
                        needsConfirm = true;
                        confirmKind = fine.confirmKind();
                        confirmSummary = fine.confirmSummary();
                    }
                    scriptsUpdated = fine.scriptsUpdated();
                    assetsUpdated = fine.assetsUpdated();
                    applied.addAll(fine.applied());
                    reply = fine.reply();
                    sendStreamEvent(emitter, cancelled, "token", Map.of("text", reply));
                } else if (pipelineIntent == PipelineIntent.GENERATE_SCRIPT) {
                    sendStreamEvent(emitter, cancelled, "status", Map.of("text", "正在生成剧本…"));
                    PipelineResult result = executeGenerateScript(
                            projectId,
                            status -> sendStreamEvent(emitter, cancelled, "status", Map.of("text", status)),
                            batch -> sendStreamEvent(emitter, cancelled, "scripts", Map.of(
                                    "phase", "append",
                                    "items", batch
                            )),
                            () -> sendStreamEvent(emitter, cancelled, "scripts", Map.of("phase", "cleared"))
                    );
                    scriptsUpdated = result.updated();
                    applied.addAll(result.applied());
                    reply = result.reply();
                    sendStreamEvent(emitter, cancelled, "token", Map.of("text", reply));
                } else if (pipelineIntent == PipelineIntent.EXTRACT_ASSETS) {
                    sendStreamEvent(emitter, cancelled, "status", Map.of("text", "正在提取资产…"));
                    PipelineResult result = executeExtractAssets(
                            projectId,
                            status -> sendStreamEvent(emitter, cancelled, "status", Map.of("text", status))
                    );
                    assetsUpdated = result.updated();
                    applied.addAll(result.applied());
                    reply = result.reply();
                    sendStreamEvent(emitter, cancelled, "token", Map.of("text", reply));
                } else {
                    ShotsIntent shotsIntent = resolveGenerateShotsIntent(
                            projectId,
                            context,
                            message,
                            status -> sendStreamEvent(emitter, cancelled, "status", Map.of("text", status)),
                            req.getSelectedShotIds()
                    );
                    if (shotsIntent.mode() == ShotsIntentMode.ASK_WHICH) {
                        if (isDeleteShotIntent(message)) {
                            reply = "请指定要删除的分镜编号，例如「删除第 1 个分镜」或「删除第 2、3 个」。"
                                    + "也可以先勾选分镜再说「删除这些分镜」。";
                        } else {
                            reply = "请指定要生成的分镜编号，例如「生成第 1 个分镜提示词」或「生成第 2、3 个」。"
                                    + "若要全部生成，请明确说「生成全部分镜提示词」。";
                        }
                        sendStreamEvent(emitter, cancelled, "token", Map.of("text", reply));
                    } else if (shotsIntent.needsDelete()) {
                        String summary = summarizeDeleteShots(shotsIntent);
                        if (!confirmed) {
                            needsConfirm = true;
                            confirmKind = "delete_shots";
                            confirmSummary = summary;
                            reply = pendingConfirmReply(summary);
                            sendStreamEvent(emitter, cancelled, "token", Map.of("text", reply));
                        } else {
                            sendStreamEvent(emitter, cancelled, "status", Map.of("text", "正在删除分镜…"));
                            GenerateShotsResult result = executeDeleteShotsIntent(
                                    projectId,
                                    shotsIntent,
                                    status -> sendStreamEvent(emitter, cancelled, "status", Map.of("text", status))
                            );
                            shotsUpdated = result.shotsUpdated();
                            applied.addAll(result.applied());
                            reply = result.reply();
                            sendStreamEvent(emitter, cancelled, "shots", Map.of("phase", "updated"));
                            sendStreamEvent(emitter, cancelled, "token", Map.of("text", reply));
                        }
                    } else if (shotsIntent.needsGenerate()) {
                        sendStreamEvent(emitter, cancelled, "status", Map.of("text", "正在生成分镜提示词…"));
                        GenerateShotsResult result = executeGenerateShotsIntent(
                                projectId,
                                shotsIntent,
                                isCancelled,
                                status -> sendStreamEvent(emitter, cancelled, "status", Map.of("text", status)),
                                (phase, shotNumber) -> {
                                    Map<String, Object> payload = new LinkedHashMap<>();
                                    payload.put("phase", phase);
                                    if (shotNumber != null) {
                                        payload.put("shotNumber", shotNumber);
                                    }
                                    sendStreamEvent(emitter, cancelled, "shots", payload);
                                },
                                (shotNumber, videoPrompt) -> sendStreamEvent(emitter, cancelled, "shots", Map.of(
                                        "phase", "streaming",
                                        "shotNumber", shotNumber,
                                        "videoPrompt", videoPrompt
                                ))
                        );
                        shotsUpdated = result.shotsUpdated();
                        applied.addAll(result.applied());
                        reply = result.reply();
                        stopped = result.stopped();
                        sendStreamEvent(emitter, cancelled, "token", Map.of("text", reply));
                    } else {
                        sendStreamEvent(emitter, cancelled, "status", Map.of("text", "理解创作意图…"));
                        String existing = loadExistingSource(projectId);
                        LlmGateway.WriteSourceIntent intent = resolveWriteSourceIntent(context, message, existing);
                        if (intent.needsSourceWrite()) {
                            ConfirmGate gate = gateDangerousWriteSource(intent, existing, confirmed);
                            if (gate != null) {
                                needsConfirm = true;
                                confirmKind = gate.kind();
                                confirmSummary = gate.summary();
                                reply = pendingConfirmReply(gate.summary());
                                sendStreamEvent(emitter, cancelled, "token", Map.of("text", reply));
                            } else {
                                WriteSourceResult result = executeWriteSourceIntent(
                                        projectId,
                                        style,
                                        message,
                                        existing,
                                        intent,
                                        isCancelled,
                                        status -> sendStreamEvent(emitter, cancelled, "status", Map.of("text", status)),
                                        content -> sendStreamEvent(emitter, cancelled, "source", Map.of("content", content))
                                );
                                sourceUpdated = result.sourceUpdated();
                                applied.addAll(result.applied());
                                reply = result.reply();
                                sendStreamEvent(emitter, cancelled, "token", Map.of("text", reply));
                            }
                        } else if (intent.mode() == LlmGateway.WriteSourceMode.ALREADY_COMPLETE) {
                            reply = alreadyCompleteReply(existing, intent.prefixTarget());
                            sendStreamEvent(emitter, cancelled, "token", Map.of("text", reply));
                        } else {
                            sendStreamEvent(emitter, cancelled, "status", Map.of("text", "助手思考中…"));
                            StringBuilder streamed = new StringBuilder();
                            reply = llmGateway.streamAssistantReply(context, message, token -> {
                                streamed.append(token);
                                sendStreamEvent(emitter, cancelled, "token", Map.of("text", token));
                            });
                            if (reply == null || reply.isBlank()) {
                                reply = streamed.toString();
                            }
                        }
                    }
                }

                if (cancelled.get() && !stopped) {
                    reply = (reply == null || reply.isBlank())
                            ? "（已停止）"
                            : reply.trim() + "\n\n（已停止）";
                    stopped = true;
                }

                reply = withChangeNote(reply, applied);
                persistAssistantReply(projectId, message, reply, false);

                if (cancelled.get() && !canSend(emitter, cancelled)) {
                    safeComplete(emitter);
                    return;
                }

                AssistantMessage latest = latestAssistantMessage(projectId);
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("id", latest != null ? latest.getId() : 0);
                done.put("role", "assistant");
                done.put("content", reply);
                done.put("createdAt", latest != null && latest.getCreatedAt() != null
                        ? latest.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        : "");
                done.put("sourceUpdated", sourceUpdated);
                done.put("shotsUpdated", shotsUpdated);
                done.put("scriptsUpdated", scriptsUpdated);
                done.put("assetsUpdated", assetsUpdated);
                done.put("appliedActions", applied);
                done.put("needsConfirm", needsConfirm);
                done.put("confirmKind", confirmKind);
                done.put("confirmSummary", confirmSummary);
                done.put("stopped", stopped);
                try {
                    sendStreamEvent(emitter, cancelled, "done", done);
                } catch (StreamCancelledException ignored) {
                    // 客户端已断开，历史已落盘
                }
                safeComplete(emitter);
            } catch (StreamCancelledException ex) {
                String stopReply = (reply == null || reply.isBlank())
                        ? "（已停止）"
                        : reply.trim() + "\n\n（已停止）";
                try {
                    persistAssistantReply(projectId, message, stopReply, false);
                } catch (Exception ignored) {
                    // 已取消时落库失败不阻塞结束
                }
                safeComplete(emitter);
            } catch (Exception ex) {
                if (cancelled.get()) {
                    String stopReply = (reply == null || reply.isBlank())
                            ? "（已停止）"
                            : reply.trim() + "\n\n（已停止）";
                    try {
                        persistAssistantReply(projectId, message, stopReply, false);
                    } catch (Exception ignored) {
                        // ignore
                    }
                    safeComplete(emitter);
                    return;
                }
                try {
                    logAssistantUsage(
                            projectId,
                            message,
                            ex.getMessage() != null ? ex.getMessage() : "流式生成失败",
                            true
                    );
                } catch (Exception ignored) {
                    // 用量日志失败不得阻塞 SSE complete，否则前端会一直「生成中」
                }
                try {
                    sendStreamEvent(emitter, cancelled, "error", Map.of(
                            "message", ex.getMessage() != null ? ex.getMessage() : "流式生成失败"
                    ));
                    safeComplete(emitter);
                } catch (Exception ignored) {
                    try {
                        emitter.completeWithError(ex);
                    } catch (Exception ignored2) {
                        // ignore
                    }
                }
            } finally {
                AiRequestOverride.clear();
                currentUserService.clearRequestUser();
            }
        });

        return emitter;
    }

    public List<AssistantDtos.MessageResponse> history(Long projectId) {
        projectService.getProject(projectId);
        return assistantMessageRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    private void logAssistantUsage(Long projectId, String userMessage, String reply, boolean failed) {
        String summary = userMessage == null || userMessage.isBlank() ? "AI 助手对话" : userMessage.trim();
        try {
            if (failed) {
                aiUsageLogService.logFailure(
                        projectId,
                        AiUsageLogService.TYPE_ASSISTANT_CHAT,
                        summary,
                        reply
                );
            } else {
                aiUsageLogService.logSuccess(
                        projectId,
                        AiUsageLogService.TYPE_ASSISTANT_CHAT,
                        summary,
                        reply
                );
            }
        } catch (Exception ignored) {
            // 登录态未传到工作线程时不应拖垮对话结束
        }
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // already completed
        }
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            throw new BusinessException("SSE 推送失败: " + ex.getMessage());
        }
    }

    private void sendStreamEvent(SseEmitter emitter, AtomicBoolean cancelled, String name, Object data) {
        if (cancelled.get()) {
            throw new StreamCancelledException();
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            cancelled.set(true);
            throw new StreamCancelledException();
        }
    }

    private boolean canSend(SseEmitter emitter, AtomicBoolean cancelled) {
        return emitter != null && !cancelled.get();
    }

    private void persistAssistantReply(Long projectId, String userMessage, String reply, boolean failed) {
        AssistantMessage assistantMsg = new AssistantMessage();
        assistantMsg.setProjectId(projectId);
        assistantMsg.setUserId(currentUserService.getCurrentUserId());
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(reply == null || reply.isBlank() ? "（已停止）" : reply);
        assistantMessageRepository.save(assistantMsg);
        logAssistantUsage(projectId, userMessage, assistantMsg.getContent(), failed);
    }

    private AssistantMessage latestAssistantMessage(Long projectId) {
        List<AssistantMessage> list = assistantMessageRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
        if (list.isEmpty()) {
            return null;
        }
        return list.get(list.size() - 1);
    }

    private static final class StreamCancelledException extends RuntimeException {}

    private record WriteSourceResult(String reply, boolean sourceUpdated, List<String> applied) {}

    private enum PipelineIntent {
        NONE,
        GENERATE_SCRIPT,
        EXTRACT_ASSETS
    }

    private record PipelineResult(String reply, boolean updated, List<String> applied) {}

    private enum ShotsIntentMode {
        GENERATE_ALL,
        GENERATE_ONE,
        GENERATE_SOME,
        REGENERATE_ONE,
        REGENERATE_SOME,
        DELETE_ONE,
        DELETE_SOME,
        ASK_WHICH,
        CHAT
    }

    private record ShotsIntent(
            ShotsIntentMode mode,
            List<Integer> shotNumbers,
            LlmGateway.PromptLanguage promptLanguage
    ) {
        static ShotsIntent chat() {
            return new ShotsIntent(ShotsIntentMode.CHAT, List.of(), null);
        }

        static ShotsIntent askWhich() {
            return new ShotsIntent(ShotsIntentMode.ASK_WHICH, List.of(), null);
        }

        ShotsIntent withLanguage(LlmGateway.PromptLanguage language) {
            return new ShotsIntent(mode, shotNumbers, language);
        }

        boolean needsGenerate() {
            return mode != ShotsIntentMode.CHAT
                    && mode != ShotsIntentMode.ASK_WHICH
                    && !needsDelete();
        }

        boolean needsDelete() {
            return mode == ShotsIntentMode.DELETE_ONE || mode == ShotsIntentMode.DELETE_SOME;
        }

        boolean targetsSpecificShots() {
            return mode == ShotsIntentMode.GENERATE_ONE
                    || mode == ShotsIntentMode.GENERATE_SOME
                    || mode == ShotsIntentMode.REGENERATE_ONE
                    || mode == ShotsIntentMode.REGENERATE_SOME;
        }
    }

    private record GenerateShotsResult(String reply, boolean shotsUpdated, List<String> applied, boolean stopped) {
        GenerateShotsResult(String reply, boolean shotsUpdated, List<String> applied) {
            this(reply, shotsUpdated, applied, false);
        }
    }

    private String loadExistingSource(Long projectId) {
        String existing = sourceTextRepository.findByProjectId(projectId)
                .map(SourceText::getContent)
                .orElse("");
        return existing == null ? "" : existing;
    }

    private static String pendingConfirmReply(String summary) {
        return "请确认后再执行：\n" + summary + "\n删除/覆盖前会自动保存快照，可在「版本快照」回滚。";
    }

    private static String summarizeDeleteShots(ShotsIntent intent) {
        List<Integer> nums = intent.shotNumbers() == null ? List.of() : intent.shotNumbers();
        String labels = nums.stream().sorted().map(n -> "#" + n).collect(Collectors.joining("、"));
        return labels.isBlank() ? "删除指定分镜" : "删除分镜 " + labels;
    }

    /** 删除章节 / 覆盖整篇原文 → 需确认；返回 null 表示可直接执行 */
    private ConfirmGate gateDangerousWriteSource(
            LlmGateway.WriteSourceIntent intent, String existing, boolean confirmed) {
        if (confirmed || intent == null) {
            return null;
        }
        if (intent.mode() == LlmGateway.WriteSourceMode.DELETE_CHAPTER) {
            Integer ch = intent.targetChapter();
            String summary = ch != null ? "从原文删除第 " + ch + " 章" : "从原文删除章节";
            return new ConfirmGate("delete_chapter", summary);
        }
        if (intent.mode() == LlmGateway.WriteSourceMode.WRITE_NEW
                && existing != null && !existing.isBlank()) {
            return new ConfirmGate(
                    "overwrite_source",
                    "用新生成内容覆盖当前原文（现有 " + existing.length() + " 字将被替换）");
        }
        return null;
    }

    /** 落库/回复末尾补一句「改了什么」，避免静默改数据 */
    private static String withChangeNote(String reply, List<String> applied) {
        if (applied == null || applied.isEmpty()) {
            return reply;
        }
        String note = describeAppliedActions(applied);
        if (note.isBlank()) {
            return reply;
        }
        String base = reply == null ? "" : reply.trim();
        if (base.contains("【本次变更】") || base.contains(note)) {
            return base;
        }
        if (base.isBlank()) {
            return "【本次变更】" + note;
        }
        return base + "\n\n【本次变更】" + note;
    }

    private static String describeAppliedActions(List<String> applied) {
        List<String> parts = new ArrayList<>();
        for (String raw : applied) {
            if (raw == null || raw.isBlank()) continue;
            if (raw.startsWith("delete_shot_")) {
                parts.add("删除分镜 #" + raw.substring("delete_shot_".length()));
            } else if (raw.startsWith("delete_script_")) {
                parts.add("删除剧本 #" + raw.substring("delete_script_".length()));
            } else if (raw.startsWith("delete_asset_")) {
                parts.add("删除资产");
            } else if (raw.startsWith("delete_chapter_")) {
                parts.add("删除第 " + raw.substring("delete_chapter_".length()) + " 章");
            } else if (raw.startsWith("rewrite_script_")) {
                parts.add("重写剧本 #" + raw.substring("rewrite_script_".length()));
            } else if (raw.startsWith("regen_asset_prompt_")) {
                parts.add("重生资产生图提示词");
            } else if (raw.startsWith("rewrite_asset_")) {
                parts.add("重写资产");
            } else if (raw.startsWith("create_asset_")) {
                parts.add("新增资产");
            } else if (raw.startsWith("regenerate_shot_")) {
                parts.add("生成分镜 #" + raw.substring("regenerate_shot_".length()) + " 提示词");
            } else if ("generate_shots".equals(raw)) {
                parts.add("生成全部分镜提示词");
            } else if ("generate_script".equals(raw) || "extract_assets".equals(raw)) {
                parts.add("generate_script".equals(raw) ? "生成剧本" : "提取资产");
            } else if ("append_source".equals(raw)) {
                parts.add("续写原文");
            } else if ("set_source".equals(raw)) {
                parts.add("写入/覆盖原文");
            } else if ("rewrite_chapter".equals(raw)) {
                parts.add("重写章节");
            } else {
                parts.add(raw);
            }
        }
        return String.join("；", parts);
    }

    /** 关键词优先；仅疑似写原文时才打 LLM 分类，避免「你好」等闲聊被拖慢 */
    private LlmGateway.WriteSourceIntent resolveWriteSourceIntent(String context, String message, String existing) {
        // 生成剧本 / 提取资产绝不能走进写原文
        if (isGenerateScriptIntent(message) || isExtractAssetsIntent(message)) {
            return LlmGateway.WriteSourceIntent.chat();
        }
        int maxChapter = existing.isBlank() ? 0 : detectMaxChapter(existing);
        // 删除章节优先：避免被普通对话模型口头“假装删除”
        if (isDeleteChapterIntent(message)) {
            Integer chapter = extractTargetChapter(message);
            if (chapter != null) {
                return LlmGateway.WriteSourceIntent.delete(chapter);
            }
        }
        // 「你好」等明显闲聊：直接跳过分类器（否则会先卡一轮死端点 / 思考链）
        if (!mightBeWriteSourceIntent(message)) {
            return LlmGateway.WriteSourceIntent.chat();
        }
        LlmGateway.WriteSourceIntent keywordIntent = inferWriteSourceIntentFromKeywords(message, existing, maxChapter);
        if (keywordIntent.needsSourceWrite()
                || keywordIntent.mode() == LlmGateway.WriteSourceMode.ALREADY_COMPLETE) {
            return keywordIntent;
        }
        LlmGateway.WriteSourceIntent llmIntent = llmGateway.classifyWriteSourceIntent(
                context, message, maxChapter, existing.isBlank());
        if (llmIntent != null && llmIntent.mode() != LlmGateway.WriteSourceMode.CHAT) {
            return normalizeWriteSourceIntent(llmIntent, message, existing, maxChapter);
        }
        if (llmIntent != null) {
            return llmIntent;
        }
        return LlmGateway.WriteSourceIntent.chat();
    }

    /** 是否可能是写/改原文意图（用于决定要不要调 LLM 分类器） */
    private boolean mightBeWriteSourceIntent(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (isWriteSourceIntent(message) || isDeleteChapterIntent(message) || isRewriteChapterIntent(message)) {
            return true;
        }
        String m = message.toLowerCase();
        return m.contains("原文")
                || m.contains("小说")
                || m.contains("章节")
                || m.contains("续写")
                || m.contains("填原文")
                || (m.contains("章") && (m.contains("写") || m.contains("生成") || m.contains("创作") || m.contains("改")));
    }

    private LlmGateway.WriteSourceIntent normalizeWriteSourceIntent(LlmGateway.WriteSourceIntent intent,
                                                                    String message, String existing,
                                                                    int maxChapter) {
        return switch (intent.mode()) {
            case REWRITE_CHAPTER -> {
                Integer chapter = intent.targetChapter() != null
                        ? intent.targetChapter()
                        : extractTargetChapter(message);
                if (chapter == null || existing.isBlank()) {
                    yield inferWriteSourceIntentFromKeywords(message, existing, maxChapter);
                }
                yield LlmGateway.WriteSourceIntent.rewrite(chapter);
            }
            case DELETE_CHAPTER -> {
                Integer chapter = intent.targetChapter() != null
                        ? intent.targetChapter()
                        : extractTargetChapter(message);
                if (chapter == null) {
                    yield LlmGateway.WriteSourceIntent.chat();
                }
                yield LlmGateway.WriteSourceIntent.delete(chapter);
            }
            case FILL_PREFIX -> normalizeFillPrefix(intent, message, existing, maxChapter);
            case APPEND_CHAPTERS -> normalizeAppend(intent, message, existing, maxChapter);
            case WRITE_NEW -> {
                if (!existing.isBlank()) {
                    Integer prefix = intent.prefixTarget() != null
                            ? intent.prefixTarget()
                            : extractPrefixChapterTarget(message);
                    if (prefix != null && maxChapter < prefix) {
                        yield LlmGateway.WriteSourceIntent.fillPrefix(prefix, maxChapter + 1, prefix);
                    }
                }
                int count = intent.chapterCount() != null
                        ? intent.chapterCount()
                        : extractChapterCount(message, 5);
                yield LlmGateway.WriteSourceIntent.writeNew(count);
            }
            default -> intent;
        };
    }

    private LlmGateway.WriteSourceIntent normalizeFillPrefix(LlmGateway.WriteSourceIntent intent,
                                                             String message, String existing,
                                                             int maxChapter) {
        Integer prefixTarget = intent.prefixTarget() != null
                ? intent.prefixTarget()
                : extractPrefixChapterTarget(message);
        if (prefixTarget == null) {
            return inferWriteSourceIntentFromKeywords(message, existing, maxChapter);
        }
        if (existing.isBlank()) {
            return LlmGateway.WriteSourceIntent.writeNew(prefixTarget);
        }
        if (maxChapter >= prefixTarget) {
            return LlmGateway.WriteSourceIntent.alreadyComplete(maxChapter, prefixTarget);
        }
        int start = intent.startChapter() != null ? intent.startChapter() : maxChapter + 1;
        int end = intent.endChapter() != null ? intent.endChapter() : prefixTarget;
        end = Math.max(end, prefixTarget);
        start = Math.min(Math.max(start, maxChapter + 1), end);
        return LlmGateway.WriteSourceIntent.fillPrefix(prefixTarget, start, end);
    }

    private LlmGateway.WriteSourceIntent normalizeAppend(LlmGateway.WriteSourceIntent intent,
                                                         String message, String existing,
                                                         int maxChapter) {
        if (existing.isBlank()) {
            int count = intent.chapterCount() != null ? intent.chapterCount() : extractChapterCount(message, 5);
            return LlmGateway.WriteSourceIntent.writeNew(count);
        }
        Integer targetChapter = intent.targetChapter() != null
                ? intent.targetChapter()
                : extractTargetChapter(message);
        if (targetChapter != null && hasChapter(existing, targetChapter) && !isRewriteChapterIntent(message)) {
            return LlmGateway.WriteSourceIntent.rewrite(targetChapter);
        }
        int start = intent.startChapter() != null
                ? intent.startChapter()
                : (targetChapter != null ? targetChapter : maxChapter + 1);
        int end = intent.endChapter() != null ? intent.endChapter() : start;
        if (end < start) {
            end = start;
        }
        return LlmGateway.WriteSourceIntent.append(start, end);
    }

    private LlmGateway.WriteSourceIntent inferWriteSourceIntentFromKeywords(String message, String existing,
                                                                            int maxChapter) {
        if (!isWriteSourceIntent(message)) {
            return LlmGateway.WriteSourceIntent.chat();
        }
        Integer targetChapter = extractTargetChapter(message);
        Integer prefixTarget = extractPrefixChapterTarget(message);

        if (isRewriteChapterIntent(message) && targetChapter != null && !existing.isBlank()) {
            return LlmGateway.WriteSourceIntent.rewrite(targetChapter);
        }
        if (targetChapter != null && !isRewriteChapterIntent(message) && !existing.isBlank()
                && hasChapter(existing, targetChapter)) {
            return LlmGateway.WriteSourceIntent.rewrite(targetChapter);
        }
        boolean fillPrefixChapters = prefixTarget != null && !existing.isBlank() && maxChapter < prefixTarget;
        boolean writeTargetChapter = targetChapter != null && !isRewriteChapterIntent(message);
        if ((isAppendSourceIntent(message) && !existing.isBlank()) || writeTargetChapter || fillPrefixChapters) {
            if (fillPrefixChapters) {
                return LlmGateway.WriteSourceIntent.fillPrefix(prefixTarget, maxChapter + 1, prefixTarget);
            }
            if (targetChapter != null) {
                return LlmGateway.WriteSourceIntent.append(targetChapter, targetChapter);
            }
            int count = extractChapterCount(message, 1);
            int start = Math.max(maxChapter + 1, 1);
            return LlmGateway.WriteSourceIntent.append(start, start + count - 1);
        }
        if (prefixTarget != null && !existing.isBlank() && maxChapter >= prefixTarget) {
            return LlmGateway.WriteSourceIntent.alreadyComplete(maxChapter, prefixTarget);
        }
        // 默认 3 章短篇；用户明确说「5 章」等再按数量来，避免一章拖很久
        int chapters = prefixTarget != null ? prefixTarget : extractChapterCount(message, 3);
        return LlmGateway.WriteSourceIntent.writeNew(chapters);
    }

    private String alreadyCompleteReply(String existing, Integer prefixTarget) {
        int maxChapter = existing.isBlank() ? 0 : detectMaxChapter(existing);
        return "原文已有 " + maxChapter + " 章，已满足「前 " + prefixTarget + " 章」。"
                + "如需重写某一章，请说「重写第 N 章」。";
    }

    private WriteSourceResult executeWriteSourceIntent(Long projectId, String style, String message,
                                                       String existing, LlmGateway.WriteSourceIntent intent,
                                                       BooleanSupplier isCancelled,
                                                       java.util.function.Consumer<String> onStatus,
                                                       java.util.function.Consumer<String> onSource) {
        return switch (intent.mode()) {
            case REWRITE_CHAPTER -> {
                if (intent.targetChapter() == null) {
                    yield new WriteSourceResult("无法确定要重写的章节。", false, List.of());
                }
                yield rewriteSingleChapter(
                        projectId, style, message, existing, intent.targetChapter(),
                        isCancelled, onStatus, onSource);
            }
            case DELETE_CHAPTER -> {
                if (intent.targetChapter() == null) {
                    yield new WriteSourceResult("无法确定要删除的章节。", false, List.of());
                }
                yield deleteSingleChapter(
                        projectId, existing, intent.targetChapter(), onStatus, onSource);
            }
            case APPEND_CHAPTERS, FILL_PREFIX -> appendChapters(
                    projectId, style, message, existing,
                    intent.startChapter(), chapterCount(intent), isCancelled, onStatus, onSource);
            case WRITE_NEW -> writeNewNovel(
                    projectId, style, message, intent.chapterCount() != null ? intent.chapterCount() : 5,
                    isCancelled, onStatus, onSource);
            default -> new WriteSourceResult("未识别的创作意图。", false, List.of());
        };
    }

    private int chapterCount(LlmGateway.WriteSourceIntent intent) {
        int start = intent.startChapter() != null ? intent.startChapter() : 1;
        int end = intent.endChapter() != null ? intent.endChapter() : start;
        return Math.max(end - start + 1, 1);
    }

    private WriteSourceResult appendChapters(Long projectId, String style, String message, String existing,
                                             int startChapter, int count, BooleanSupplier isCancelled,
                                             java.util.function.Consumer<String> onStatus,
                                             java.util.function.Consumer<String> onSource) {
        if (onStatus != null) {
            onStatus.accept("正在续写第 " + startChapter
                    + (count > 1 ? ("–" + (startChapter + count - 1)) : "")
                    + " 章，写入原文…");
        }
        StringBuilder live = new StringBuilder(existing.trim());
        if (!live.isEmpty()) {
            live.append("\n\n");
        }
        String appended;
        try {
            appended = llmGateway.streamAppendChapters(
                    message, style, existing, startChapter, count,
                    token -> {
                        live.append(token);
                        if (onSource != null) {
                            onSource.accept(live.toString());
                        }
                    },
                    onStatus,
                    isCancelled
            );
        } catch (RuntimeException ex) {
            if (isCancelled == null || !isCancelled.getAsBoolean()) {
                throw ex;
            }
            appended = deriveAppendedContent(live, existing);
        }
        if (isCancelled != null && isCancelled.getAsBoolean()) {
            String finalContent = buildAppendedContent(existing, appended, live);
            if (!finalContent.equals(existing.trim()) && !finalContent.isBlank()) {
                saveSourceContent(projectId, finalContent, false);
                return new WriteSourceResult("已停止，部分内容已写入原文。", true, List.of("append_source"));
            }
            return new WriteSourceResult("已停止生成。", false, List.of());
        }
        String finalContent = buildAppendedContent(existing, appended, live);
        if (onSource != null) {
            onSource.accept(finalContent);
        }
        saveSourceContent(projectId, finalContent, false);
        String reply = "已续写第 " + startChapter
                + (count > 1 ? ("–" + (startChapter + count - 1)) : "")
                + " 章并写入原文末尾。请在左侧「原文」查看。";
        return new WriteSourceResult(reply, true, List.of("append_source"));
    }

    private WriteSourceResult writeNewNovel(Long projectId, String style, String message, int chapters,
                                            BooleanSupplier isCancelled,
                                            java.util.function.Consumer<String> onStatus,
                                            java.util.function.Consumer<String> onSource) {
        if (onStatus != null) {
            onStatus.accept("正在生成 " + chapters + " 章并写入原文…");
        }
        StringBuilder live = new StringBuilder();
        String novel;
        try {
            novel = llmGateway.streamNovelByChapters(
                    message, style, chapters,
                    token -> {
                        live.append(token);
                        if (onSource != null) {
                            onSource.accept(live.toString());
                        }
                    },
                    onStatus,
                    isCancelled
            );
        } catch (RuntimeException ex) {
            if (isCancelled == null || !isCancelled.getAsBoolean()) {
                throw ex;
            }
            novel = live.toString().trim();
        }
        if (isCancelled != null && isCancelled.getAsBoolean()) {
            if (!novel.isBlank()) {
                saveSourceContent(projectId, novel, false);
                return new WriteSourceResult("已停止，部分内容已写入原文。", true, List.of("set_source"));
            }
            return new WriteSourceResult("已停止生成。", false, List.of());
        }
        if (onSource != null) {
            onSource.accept(novel);
        }
        saveSourceContent(projectId, novel, false);
        String reply = "已将 " + chapters + " 章写入「原文」（共 " + novel.length()
                + " 字）。请在左侧原文区查看，可再点「生成剧本」。";
        return new WriteSourceResult(reply, true, List.of("set_source"));
    }

    private String buildAppendedContent(String existing, String appended, StringBuilder live) {
        if (appended != null && !appended.isBlank()) {
            return existing.trim() + (existing.isBlank() ? "" : "\n\n") + appended;
        }
        return live.toString().trim();
    }

    private String deriveAppendedContent(StringBuilder live, String existing) {
        String liveStr = live.toString().trim();
        String base = existing.trim();
        if (base.isBlank()) {
            return liveStr;
        }
        if (liveStr.startsWith(base)) {
            return liveStr.substring(base.length()).trim();
        }
        return liveStr;
    }

    private WriteSourceResult rewriteSingleChapter(Long projectId, String style, String message,
                                                   String existing, int chapterNo, BooleanSupplier isCancelled,
                                                   java.util.function.Consumer<String> onStatus,
                                                   java.util.function.Consumer<String> onSource) {
        ChapterSlice slice = splitAroundChapter(existing, chapterNo);
        if (onStatus != null) {
            onStatus.accept("正在重写第 " + chapterNo + " 章，直接更新原文…");
        }
        String contextForLlm = slice.before()
                + "\n（第" + chapterNo + "章待重写，请勿重复输出其它章节）\n"
                + slice.after();
        StringBuilder chapterBuf = new StringBuilder();
        String generated;
        try {
            generated = llmGateway.streamAppendChapters(
                    message + "（请只重写第" + chapterNo + "章，承接前后文）",
                    style,
                    contextForLlm,
                    chapterNo,
                    1,
                    token -> {
                        chapterBuf.append(token);
                        if (onSource != null) {
                            onSource.accept(joinChapter(slice.before(), chapterBuf.toString(), slice.after()));
                        }
                    },
                    onStatus,
                    isCancelled
            );
        } catch (RuntimeException ex) {
            if (isCancelled == null || !isCancelled.getAsBoolean()) {
                throw ex;
            }
            generated = chapterBuf.toString();
        }
        if (isCancelled != null && isCancelled.getAsBoolean()) {
            if (!chapterBuf.isEmpty()) {
                String finalContent = joinChapter(slice.before(), chapterBuf.toString(), slice.after());
                saveSourceContent(projectId, finalContent, false);
                return new WriteSourceResult("已停止，第 " + chapterNo + " 章部分改写已保留。", true, List.of("rewrite_chapter"));
            }
            return new WriteSourceResult("已停止生成。", false, List.of());
        }
        String finalContent = joinChapter(slice.before(), generated, slice.after());
        if (onSource != null) {
            onSource.accept(finalContent);
        }
        saveSourceContent(projectId, finalContent, false);
        String reply = "已重写第 " + chapterNo + " 章并更新到左侧「原文」，其它章节未改动。";
        return new WriteSourceResult(reply, true, List.of("rewrite_chapter"));
    }

    private WriteSourceResult deleteSingleChapter(Long projectId, String existing, int chapterNo,
                                                  java.util.function.Consumer<String> onStatus,
                                                  java.util.function.Consumer<String> onSource) {
        if (existing == null || existing.isBlank()) {
            return new WriteSourceResult("原文为空，没有可删除的章节。", false, List.of());
        }
        if (!hasChapter(existing, chapterNo)) {
            return new WriteSourceResult("原文中没有第 " + chapterNo + " 章。", false, List.of());
        }
        if (onStatus != null) {
            onStatus.accept("正在从原文删除第 " + chapterNo + " 章…");
        }
        // 删除不可逆，先自动留档便于回滚
        workspaceService.createManualSnapshot(projectId, "删除第 " + chapterNo + " 章前");
        ChapterSlice slice = splitAroundChapter(existing, chapterNo);
        String after = renumberChaptersAfterDelete(slice.after(), chapterNo);
        String finalContent = joinChapter(slice.before(), "", after);
        if (onSource != null) {
            onSource.accept(finalContent);
        }
        saveSourceContent(projectId, finalContent, false);
        int remaining = detectMaxChapter(finalContent);
        String reply = "已从左侧「原文」删除第 " + chapterNo + " 章"
                + (remaining > 0 ? "，后续章节已顺延编号（现共 " + remaining + " 章）" : "")
                + "。可在「版本快照」回滚。";
        return new WriteSourceResult(reply, true, List.of("delete_chapter_" + chapterNo));
    }

    /** 删除第 N 章后，把后面的第 N+1、N+2… 顺延为第 N、N+1… */
    private String renumberChaptersAfterDelete(String after, int deletedChapter) {
        if (after == null || after.isBlank()) {
            return "";
        }
        int max = detectMaxChapter(after);
        if (max <= deletedChapter) {
            return after;
        }
        String result = after;
        // 从大到小替换，避免「第10章」被误伤成「第9章」后再被改成「第8章」
        for (int n = max; n > deletedChapter; n--) {
            String fromDigit = "第" + n + "章";
            String fromCn = "第" + toChineseNumber(n) + "章";
            String to = "第" + (n - 1) + "章";
            result = result.replace(fromDigit, to);
            if (!fromCn.equals(fromDigit)) {
                result = result.replace(fromCn, to);
            }
        }
        return result;
    }

    private record ChapterSlice(String before, String oldChapter, String after) {}

    private boolean hasChapter(String text, int chapterNo) {
        java.util.regex.Pattern startPat = java.util.regex.Pattern.compile(
                "第\\s*(?:" + chapterNo + "|" + toChineseNumber(chapterNo) + ")\\s*章");
        return startPat.matcher(text).find();
    }

    private ChapterSlice splitAroundChapter(String text, int chapterNo) {
        java.util.regex.Pattern startPat = java.util.regex.Pattern.compile(
                "第\\s*(?:" + chapterNo + "|" + toChineseNumber(chapterNo) + ")\\s*章[^\\n]*\\n?");
        java.util.regex.Matcher start = startPat.matcher(text);
        if (!start.find()) {
            // 找不到该章：插在全文末尾当作新章
            String before = text.isBlank() ? "" : text.trim() + "\n\n";
            return new ChapterSlice(before, "", "");
        }
        int from = start.start();
        java.util.regex.Matcher next = java.util.regex.Pattern
                .compile("第\\s*(\\d+|[一二三四五六七八九十两]+)\\s*章")
                .matcher(text);
        int to = text.length();
        if (next.find(start.end())) {
            to = next.start();
        }
        return new ChapterSlice(
                text.substring(0, from),
                text.substring(from, to),
                text.substring(to)
        );
    }

    private String joinChapter(String before, String chapter, String after) {
        String mid = chapter == null ? "" : chapter.trim();
        StringBuilder sb = new StringBuilder();
        if (before != null && !before.isEmpty()) {
            sb.append(before);
            if (!before.endsWith("\n") && !mid.isEmpty()) {
                sb.append("\n");
            }
        }
        sb.append(mid);
        if (after != null && !after.isBlank()) {
            if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
                sb.append("\n\n");
            } else if (!sb.isEmpty() && !sb.toString().endsWith("\n\n")) {
                sb.append("\n");
            }
            sb.append(after.replaceFirst("^\\s+", ""));
        }
        return sb.toString().trim();
    }

    /** LLM 意图分类优先，失败或未识别时走关键词兜底 */
    private ShotsIntent resolveGenerateShotsIntent(
            Long projectId,
            String context,
            String message,
            Consumer<String> statusUpdater,
            List<Long> selectedShotIds
    ) {
        // 删除分镜优先，避免被聊天模型「假装删除」
        ShotsIntent deleteIntent = resolveDeleteShotsIntent(projectId, message, selectedShotIds);
        if (deleteIntent.needsDelete()) {
            return deleteIntent;
        }

        if (!mightBeShotsIntent(message) && !refersToSelectedShots(message, selectedShotIds)) {
            return ShotsIntent.chat();
        }
        int totalShots = countProjectShots(projectId);
        if (statusUpdater != null) {
            statusUpdater.accept("理解分镜意图…");
        }

        LlmGateway.PromptLanguage keywordLang = extractPromptLanguage(message);
        LlmGateway.PromptLanguage llmLang = null;

        // 优先：用户说「这些/选中」且工作台有勾选
        ShotsIntent selectedIntent = intentFromSelectedShots(projectId, message, selectedShotIds);
        if (selectedIntent.needsGenerate()) {
            return applyPromptLanguage(selectedIntent, keywordLang, null);
        }

        LlmGateway.ShotsClassifyIntent llmIntent = llmGateway.classifyShotsIntent(context, message, totalShots);
        if (llmIntent != null) {
            llmLang = llmIntent.promptLanguage();
        }
        if (llmIntent != null && llmIntent.needsGenerate()) {
            ShotsIntent mapped = mapClassifiedShotsIntent(llmIntent, totalShots, message);
            if (mapped.needsGenerate()) {
                return applyPromptLanguage(mapped, keywordLang, llmLang);
            }
        }
        ShotsIntent keywordIntent = inferShotsIntentFromKeywords(message, totalShots);
        if (keywordIntent.needsGenerate()) {
            return applyPromptLanguage(keywordIntent, keywordLang, llmLang);
        }
        if (isGenerateShotsIntent(message) && !explicitAllShots(message)
                && extractShotNumbers(message, totalShots).isEmpty()) {
            // 没写编号但已勾选分镜 → 默认处理勾选
            if (selectedIntent.mode() != ShotsIntentMode.CHAT) {
                return applyPromptLanguage(selectedIntent, keywordLang, llmLang);
            }
            return ShotsIntent.askWhich();
        }
        if (llmIntent != null && llmIntent.mode() == LlmGateway.ShotsClassifyMode.CHAT) {
            return ShotsIntent.chat();
        }
        return ShotsIntent.chat();
    }

    private ShotsIntent applyPromptLanguage(
            ShotsIntent intent,
            LlmGateway.PromptLanguage keywordLang,
            LlmGateway.PromptLanguage llmLang
    ) {
        LlmGateway.PromptLanguage resolved = keywordLang != null
                ? keywordLang
                : (llmLang != null ? llmLang : LlmGateway.PromptLanguage.ZH);
        return intent.withLanguage(resolved);
    }

    /**
     * 从用户话里识别提示词语言偏好。关键词优先于 LLM 分类器。
     * 未提及则返回 null（由调用方回落到中文默认）。
     */
    private LlmGateway.PromptLanguage extractPromptLanguage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String m = message.toLowerCase();
        boolean wantZh = m.contains("中文")
                || m.contains("汉语")
                || m.contains("国语")
                || m.contains("chinese")
                || m.contains("用中文")
                || m.contains("要中文")
                || m.contains("换成中文")
                || m.contains("改成中文")
                || m.contains("写成中文")
                || m.contains("输出中文");
        boolean wantEn = m.contains("英文")
                || m.contains("英语")
                || m.contains("english")
                || m.contains("用英文")
                || m.contains("要英文")
                || m.contains("换成英文")
                || m.contains("改成英文")
                || m.contains("写成英文")
                || m.contains("输出英文");
        // 同时出现时：后出现的优先（「不要英文，要中文」）
        if (wantZh && wantEn) {
            int zhPos = Math.max(m.lastIndexOf("中文"), Math.max(m.lastIndexOf("汉语"), m.lastIndexOf("chinese")));
            int enPos = Math.max(m.lastIndexOf("英文"), Math.max(m.lastIndexOf("英语"), m.lastIndexOf("english")));
            return zhPos >= enPos ? LlmGateway.PromptLanguage.ZH : LlmGateway.PromptLanguage.EN;
        }
        if (wantZh) {
            return LlmGateway.PromptLanguage.ZH;
        }
        if (wantEn) {
            return LlmGateway.PromptLanguage.EN;
        }
        return null;
    }

    private boolean isDeleteShotIntent(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (isDeleteChapterIntent(message)) {
            return false;
        }
        boolean hasDelete = message.contains("删除") || message.contains("删掉") || message.contains("去掉")
                || message.contains("移除");
        boolean hasShot = message.contains("分镜") || message.contains("镜头");
        return hasDelete && hasShot;
    }

    private ShotsIntent resolveDeleteShotsIntent(Long projectId, String message, List<Long> selectedShotIds) {
        if (!isDeleteShotIntent(message)) {
            return ShotsIntent.chat();
        }
        int totalShots = countProjectShots(projectId);
        List<Integer> nums = extractShotNumbers(message, totalShots);

        // 「第一个/第一条分镜」
        if (nums.isEmpty() && (message.contains("第一") || message.contains("首个") || message.contains("开头"))) {
            nums = List.of(1);
        }
        // 「最后一个」
        if (nums.isEmpty() && message.contains("最后") && totalShots > 0) {
            nums = List.of(totalShots);
        }
        // 勾选分镜
        if (nums.isEmpty() && selectedShotIds != null && !selectedShotIds.isEmpty()) {
            List<Shot> shots = shotRepository.findByProjectIdWithRefs(projectId);
            List<Integer> selected = new ArrayList<>();
            for (Shot shot : shots) {
                if (selectedShotIds.contains(shot.getId())) {
                    addShotNumber(selected, shot.getOrderIndex() + 1);
                }
            }
            nums = selected;
        }

        if (nums.isEmpty()) {
            return ShotsIntent.askWhich();
        }
        ShotsIntentMode mode = nums.size() == 1 ? ShotsIntentMode.DELETE_ONE : ShotsIntentMode.DELETE_SOME;
        return new ShotsIntent(mode, nums, null);
    }

    private GenerateShotsResult executeDeleteShotsIntent(
            Long projectId,
            ShotsIntent intent,
            Consumer<String> statusUpdater
    ) {
        List<Integer> targets = new ArrayList<>(intent.shotNumbers());
        targets.sort(Integer::compareTo);
        if (targets.isEmpty()) {
            return new GenerateShotsResult("请指定要删除的分镜编号。", false, List.of());
        }

        workspaceService.createManualSnapshot(projectId,
                "删除分镜 " + targets.stream().map(n -> "#" + n).collect(Collectors.joining("、")) + " 前");

        // 从大编号删到小编号，避免顺序重排后删错
        List<Integer> descending = new ArrayList<>(targets);
        descending.sort((a, b) -> Integer.compare(b, a));
        List<String> applied = new ArrayList<>();
        List<Integer> deleted = new ArrayList<>();

        for (Integer shotNum : descending) {
            if (statusUpdater != null) {
                statusUpdater.accept("正在删除分镜 #" + shotNum + "…");
            }
            List<Shot> existing = shotRepository.findByProjectIdWithRefs(projectId);
            Shot target = existing.stream()
                    .filter(s -> s.getOrderIndex() + 1 == shotNum)
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                continue;
            }
            workspaceService.deleteShot(projectId, target.getId());
            applied.add("delete_shot_" + shotNum);
            deleted.add(shotNum);
        }

        if (deleted.isEmpty()) {
            return new GenerateShotsResult("未找到要删除的分镜。", false, List.of());
        }
        deleted.sort(Integer::compareTo);
        String labels = deleted.stream().map(n -> "#" + n).collect(Collectors.joining("、"));
        int remain = shotRepository.findByProjectIdWithRefs(projectId).size();
        return new GenerateShotsResult(
                "已删除分镜 " + labels + "，当前剩余 " + remain + " 个。可在「版本快照」回滚。",
                true,
                applied
        );
    }

    private boolean refersToSelectedShots(String message, List<Long> selectedShotIds) {
        if (message == null || selectedShotIds == null || selectedShotIds.isEmpty()) {
            return false;
        }
        return message.contains("选中")
                || message.contains("这些")
                || message.contains("勾选")
                || message.contains("当前选")
                || message.contains("已选");
    }

    private ShotsIntent intentFromSelectedShots(Long projectId, String message, List<Long> selectedShotIds) {
        if (selectedShotIds == null || selectedShotIds.isEmpty()) {
            return ShotsIntent.chat();
        }
        boolean mentionSelected = refersToSelectedShots(message, selectedShotIds);
        boolean vagueShotRequest = isGenerateShotsIntent(message)
                && !explicitAllShots(message)
                && extractShotNumbers(message, 100).isEmpty();
        if (!mentionSelected && !vagueShotRequest) {
            return ShotsIntent.chat();
        }
        List<Shot> shots = shotRepository.findByProjectIdWithRefs(projectId);
        List<Integer> nums = new ArrayList<>();
        for (Shot shot : shots) {
            if (selectedShotIds.contains(shot.getId())) {
                addShotNumber(nums, shot.getOrderIndex() + 1);
            }
        }
        if (nums.isEmpty()) {
            return ShotsIntent.chat();
        }
        boolean regenerate = isRegenerateShotIntent(message) || mentionSelected;
        ShotsIntentMode mode = regenerate
                ? (nums.size() == 1 ? ShotsIntentMode.REGENERATE_ONE : ShotsIntentMode.REGENERATE_SOME)
                : (nums.size() == 1 ? ShotsIntentMode.GENERATE_ONE : ShotsIntentMode.GENERATE_SOME);
        return new ShotsIntent(mode, nums, null);
    }

    private int countProjectShots(Long projectId) {
        int totalShots = shotRepository.findByProjectIdWithRefs(projectId).size();
        if (totalShots == 0) {
            totalShots = scriptEntryRepository.findByProjectIdOrderByOrderIndexAsc(projectId).size();
        }
        return totalShots;
    }

    private ShotsIntent mapClassifiedShotsIntent(LlmGateway.ShotsClassifyIntent intent, int totalShots, String message) {
        if (intent.mode() == LlmGateway.ShotsClassifyMode.GENERATE_ALL) {
            // 模型常把「生成分镜提示词」误判成全部；没有明确「全部/所有」时不执行全量
            if (!explicitAllShots(message)) {
                List<Integer> nums = extractShotNumbers(message, totalShots);
                if (!nums.isEmpty()) {
                    boolean regenerate = isRegenerateShotIntent(message);
                    ShotsIntentMode mode = regenerate
                            ? (nums.size() == 1 ? ShotsIntentMode.REGENERATE_ONE : ShotsIntentMode.REGENERATE_SOME)
                            : (nums.size() == 1 ? ShotsIntentMode.GENERATE_ONE : ShotsIntentMode.GENERATE_SOME);
                    return new ShotsIntent(mode, nums, null);
                }
                return ShotsIntent.askWhich();
            }
            return new ShotsIntent(ShotsIntentMode.GENERATE_ALL, List.of(), null);
        }
        List<Integer> shotNums = new ArrayList<>(intent.shotNumbers());
        if (intent.includeLast() && totalShots > 0) {
            addShotNumber(shotNums, totalShots);
        }
        // 再与关键词解析合并，避免模型漏提「第一条」等
        for (Integer n : extractShotNumbers(message, totalShots)) {
            addShotNumber(shotNums, n);
        }
        shotNums.sort(Integer::compareTo);
        if (shotNums.isEmpty()) {
            return ShotsIntent.askWhich();
        }
        boolean regenerate = intent.mode() == LlmGateway.ShotsClassifyMode.REGENERATE_SOME
                || isRegenerateShotIntent(message);
        ShotsIntentMode mode;
        if (regenerate) {
            mode = shotNums.size() == 1 ? ShotsIntentMode.REGENERATE_ONE : ShotsIntentMode.REGENERATE_SOME;
        } else {
            mode = shotNums.size() == 1 ? ShotsIntentMode.GENERATE_ONE : ShotsIntentMode.GENERATE_SOME;
        }
        return new ShotsIntent(mode, shotNums, null);
    }

    private ShotsIntent inferShotsIntentFromKeywords(String message, int totalShots) {
        if (!isGenerateShotsIntent(message)) {
            return ShotsIntent.chat();
        }
        if (explicitAllShots(message)) {
            return new ShotsIntent(ShotsIntentMode.GENERATE_ALL, List.of(), null);
        }
        List<Integer> shotNums = extractShotNumbers(message, totalShots);
        if (!shotNums.isEmpty()) {
            boolean regenerate = isRegenerateShotIntent(message);
            ShotsIntentMode mode;
            if (regenerate) {
                mode = shotNums.size() == 1 ? ShotsIntentMode.REGENERATE_ONE : ShotsIntentMode.REGENERATE_SOME;
            } else {
                mode = shotNums.size() == 1 ? ShotsIntentMode.GENERATE_ONE : ShotsIntentMode.GENERATE_SOME;
            }
            return new ShotsIntent(mode, shotNums, null);
        }
        // 未指定编号：不要默认全量生成
        return ShotsIntent.askWhich();
    }

    private boolean explicitAllShots(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("全部")
                || message.contains("所有")
                || message.contains("全部分镜")
                || message.contains("一键生成")
                || message.contains("一键全部");
    }

    private boolean mightBeShotsIntent(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        // 「第N个分镜的剧本…」交给剧本重写，不要进提示词分类
        if (isRewriteScriptIntent(message) || isDeleteScriptIntent(message)) {
            return false;
        }
        if (isGenerateScriptIntent(message)) {
            return false;
        }
        String m = message.toLowerCase();
        return m.contains("分镜")
                || m.contains("镜头")
                || m.contains("提示词")
                || m.contains("prompt")
                || m.contains("shot")
                || m.contains("storyboard")
                || m.contains("视频");
    }

    private GenerateShotsResult executeGenerateShotsIntent(
            Long projectId,
            ShotsIntent intent,
            BooleanSupplier isCancelled,
            Consumer<String> statusUpdater,
            java.util.function.BiConsumer<String, Integer> shotsNotifier,
            java.util.function.BiConsumer<Integer, String> shotStreamNotifier
    ) {
        List<String> applied = new ArrayList<>();
        LlmGateway.PromptLanguage promptLanguage = intent.promptLanguage() != null
                ? intent.promptLanguage()
                : LlmGateway.PromptLanguage.ZH;
        String langHint = promptLanguage.isChinese() ? "中文" : null;
        if (intent.targetsSpecificShots()) {
            ensureNotCancelled(isCancelled);
            workspaceService.ensureShotPlaceholders(projectId);
            if (shotsNotifier != null) {
                shotsNotifier.accept("placeholders", null);
            }
            List<Shot> existing = shotRepository.findByProjectIdWithRefs(projectId);
            List<Integer> targets = intent.shotNumbers();
            int completed = 0;
            for (Integer shotNum : targets) {
                if (isCancelled != null && isCancelled.getAsBoolean()) {
                    return stoppedShotsResult(applied, completed, targets.size(), true);
                }
                if (statusUpdater != null) {
                    statusUpdater.accept("正在生成分镜 #" + shotNum + " 的"
                            + (langHint != null ? langHint : "") + "提示词…");
                }
                Shot target = existing.stream()
                        .filter(s -> s.getOrderIndex() + 1 == shotNum)
                        .findFirst()
                        .orElseThrow(() -> new BusinessException("分镜 #" + shotNum + " 不存在"));
                final int[] lastStreamLen = {0};
                try {
                    workspaceService.regenerateShotPrompts(
                            projectId, target.getId(), true, partial -> {
                        if (isCancelled != null && isCancelled.getAsBoolean()) {
                            throw new StreamCancelledException();
                        }
                        if (shotStreamNotifier == null || partial == null) {
                            return;
                        }
                        if (partial.length() - lastStreamLen[0] < 6 && partial.length() < 32) {
                            return;
                        }
                        lastStreamLen[0] = partial.length();
                        shotStreamNotifier.accept(shotNum, partial);
                    }, promptLanguage);
                } catch (StreamCancelledException ex) {
                    return stoppedShotsResult(applied, completed, targets.size(), true);
                } catch (RuntimeException ex) {
                    if (isCancelled != null && isCancelled.getAsBoolean()) {
                        return stoppedShotsResult(applied, completed, targets.size(), true);
                    }
                    throw ex;
                }
                applied.add("regenerate_shot_" + shotNum);
                completed++;
                if (shotsNotifier != null) {
                    shotsNotifier.accept("updated", shotNum);
                }
            }
            String labels = targets.stream().map(n -> "#" + n).collect(Collectors.joining("、"));
            boolean regenerate = intent.mode() == ShotsIntentMode.REGENERATE_ONE
                    || intent.mode() == ShotsIntentMode.REGENERATE_SOME;
            String action = regenerate ? "重新生成" : "生成";
            String langPart = langHint != null ? langHint : "";
            return new GenerateShotsResult(
                    "已" + action + "分镜 " + labels + " 的" + langPart + "提示词，请在中栏查看。",
                    true,
                    applied
            );
        }

        // 全量：优先按已有分镜逐条重生，便于中途停止；没有分镜时再走整批生成
        ensureNotCancelled(isCancelled);
        workspaceService.ensureShotPlaceholders(projectId);
        List<Shot> existing = shotRepository.findByProjectIdWithRefs(projectId);
        if (!existing.isEmpty()) {
            if (shotsNotifier != null) {
                shotsNotifier.accept("placeholders", null);
            }
            int completed = 0;
            for (Shot shot : existing) {
                if (isCancelled != null && isCancelled.getAsBoolean()) {
                    return stoppedShotsResult(applied, completed, existing.size(), true);
                }
                int shotNum = shot.getOrderIndex() + 1;
                if (statusUpdater != null) {
                    statusUpdater.accept("正在生成分镜 #" + shotNum + " 的"
                            + (langHint != null ? langHint : "") + "提示词…");
                }
                final int[] lastStreamLen = {0};
                try {
                    workspaceService.regenerateShotPrompts(
                            projectId, shot.getId(), false, partial -> {
                        if (isCancelled != null && isCancelled.getAsBoolean()) {
                            throw new StreamCancelledException();
                        }
                        if (shotStreamNotifier == null || partial == null) {
                            return;
                        }
                        if (partial.length() - lastStreamLen[0] < 6 && partial.length() < 32) {
                            return;
                        }
                        lastStreamLen[0] = partial.length();
                        shotStreamNotifier.accept(shotNum, partial);
                    }, promptLanguage);
                } catch (StreamCancelledException ex) {
                    return stoppedShotsResult(applied, completed, existing.size(), true);
                } catch (RuntimeException ex) {
                    if (isCancelled != null && isCancelled.getAsBoolean()) {
                        return stoppedShotsResult(applied, completed, existing.size(), true);
                    }
                    throw ex;
                }
                applied.add("regenerate_shot_" + shotNum);
                completed++;
                if (shotsNotifier != null) {
                    shotsNotifier.accept("updated", shotNum);
                }
            }
            String langPart = langHint != null ? langHint : "";
            return new GenerateShotsResult(
                    "已生成 " + existing.size() + " 个" + langPart + "分镜提示词，请在中栏「分镜提示词」列查看。",
                    true,
                    applied
            );
        }

        if (statusUpdater != null) {
            statusUpdater.accept("正在根据剧本生成全部分镜提示词…");
        }
        List<WorkspaceDtos.ShotResponse> shots = workspaceService.generateShots(projectId, promptLanguage);
        applied.add("generate_shots");
        if (shotsNotifier != null) {
            shotsNotifier.accept("updated", null);
        }
        String langPart = langHint != null ? langHint : "";
        return new GenerateShotsResult(
                "已根据当前剧本生成 " + shots.size() + " 个" + langPart + "分镜提示词，请在中栏「分镜提示词」列查看。",
                true,
                applied
        );
    }

    private GenerateShotsResult stoppedShotsResult(List<String> applied, int completed, int total, boolean updated) {
        String reply = completed > 0
                ? "已停止（完成 " + completed + "/" + total + "）。已生成的分镜可在中栏查看。"
                : "已停止生成分镜提示词。";
        return new GenerateShotsResult(reply, updated && completed > 0, applied, true);
    }

    private void ensureNotCancelled(BooleanSupplier isCancelled) {
        if (isCancelled != null && isCancelled.getAsBoolean()) {
            throw new StreamCancelledException();
        }
    }

    private PipelineIntent resolvePipelineIntent(String message) {
        if (isGenerateScriptIntent(message)) {
            return PipelineIntent.GENERATE_SCRIPT;
        }
        if (isExtractAssetsIntent(message)) {
            return PipelineIntent.EXTRACT_ASSETS;
        }
        return PipelineIntent.NONE;
    }

    /** 「生成剧本」流水线：不要误判成写原文/分镜/单条重写 */
    private boolean isGenerateScriptIntent(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (message.contains("分镜") || message.contains("镜头")) {
            return false;
        }
        if (isRewriteScriptIntent(message) || isDeleteScriptIntent(message)) {
            return false;
        }
        if (!message.contains("剧本")) {
            return false;
        }
        return message.contains("生成")
                || message.contains("写")
                || message.contains("创作")
                || message.contains("转成")
                || message.contains("转为")
                || message.contains("转换");
    }

    private boolean isExtractAssetsIntent(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (message.contains("分镜") || message.contains("提示词") && !message.contains("提取")) {
            return false;
        }
        if (isFineGrainedAssetIntent(message)) {
            return false;
        }
        boolean hasAsset = message.contains("资产");
        boolean hasExtract = message.contains("提取") || message.contains("抽取") || message.contains("导出资产");
        return hasAsset && hasExtract;
    }

    private PipelineResult executeGenerateScript(Long projectId, Consumer<String> statusUpdater) {
        return executeGenerateScript(projectId, statusUpdater, null, null);
    }

    private PipelineResult executeGenerateScript(
            Long projectId,
            Consumer<String> statusUpdater,
            Consumer<List<WorkspaceDtos.ScriptResponse>> scriptsBatchUpdater,
            Runnable onCleared) {
        if (statusUpdater != null) {
            statusUpdater.accept("正在根据原文生成剧本…");
        }
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        workspaceService.generateScriptStreaming(projectId, new WorkspaceService.ScriptStreamListener() {
            @Override
            public void onStatus(String text) {
                if (statusUpdater != null) {
                    statusUpdater.accept(text);
                }
            }

            @Override
            public void onCleared() {
                if (onCleared != null) {
                    onCleared.run();
                }
            }

            @Override
            public void onScripts(
                    List<WorkspaceDtos.ScriptResponse> batch, int chunkIndex, int chunkTotal) {
                count.addAndGet(batch.size());
                if (scriptsBatchUpdater != null) {
                    scriptsBatchUpdater.accept(batch);
                }
            }

            @Override
            public void onDone(
                    List<WorkspaceDtos.ScriptResponse> scripts,
                    List<WorkspaceDtos.ShotResponse> shots,
                    List<WorkspaceDtos.AssetResponse> assets) {
                count.set(scripts.size());
            }
        });
        return new PipelineResult(
                "已根据原文生成 " + count.get() + " 条剧本，请在工作台「剧本」列查看。",
                true,
                List.of("generate_script")
        );
    }

    private PipelineResult executeExtractAssets(Long projectId, Consumer<String> statusUpdater) {
        if (statusUpdater != null) {
            statusUpdater.accept("正在从剧本提取资产…");
        }
        List<WorkspaceDtos.AssetResponse> collected = new ArrayList<>();
        workspaceService.extractAssetsStreaming(projectId, new WorkspaceService.AssetExtractListener() {
            @Override
            public void onStatus(String text) {
                if (statusUpdater != null && text != null && !text.isBlank()) {
                    statusUpdater.accept(text);
                }
            }

            @Override
            public void onDone(List<WorkspaceDtos.AssetResponse> assets) {
                collected.addAll(assets);
            }
        });
        return new PipelineResult(
                "已提取 " + collected.size() + " 个资产，请在「资产库」查看。",
                true,
                List.of("extract_assets")
        );
    }

    private record ItemOpResult(
            String reply,
            boolean scriptsUpdated,
            boolean assetsUpdated,
            List<String> applied,
            boolean needsConfirm,
            String confirmKind,
            String confirmSummary
    ) {
        ItemOpResult(String reply, boolean scriptsUpdated, boolean assetsUpdated, List<String> applied) {
            this(reply, scriptsUpdated, assetsUpdated, applied, false, null, null);
        }

        static ItemOpResult pending(String kind, String summary) {
            return new ItemOpResult(
                    pendingConfirmReply(summary), false, false, List.of(), true, kind, summary);
        }
    }

    private record ConfirmGate(String kind, String summary) {}

    /** 剧本/资产细粒度操作；无法识别时返回 null */
    private ItemOpResult tryExecuteScriptAssetOps(
            Long projectId,
            String message,
            List<Long> selectedShotIds,
            String focus,
            Long focusId,
            Consumer<String> statusUpdater,
            boolean confirmed
    ) {
        if (isRewriteScriptIntent(message) || isDeleteScriptIntent(message)) {
            return executeScriptOps(projectId, message, selectedShotIds, focus, focusId, statusUpdater, confirmed);
        }
        if (isFineGrainedAssetIntent(message)) {
            return executeAssetOps(projectId, message, focus, focusId, statusUpdater, confirmed);
        }
        return null;
    }

    private boolean isRewriteScriptIntent(String message) {
        if (message == null || message.isBlank()) return false;
        // 「第N个分镜的提示词」→ 提示词，不是剧本
        if (isShotPromptTarget(message)) return false;
        // 「第N个分镜的剧本重新生成」：含「分镜」但仍是剧本重写（分镜号=剧本条号）
        if (!(message.contains("剧本") || message.contains("条目"))) return false;
        return message.contains("重写")
                || message.contains("重新生成")
                || message.contains("重新写")
                || message.contains("改写")
                || message.contains("优化剧本")
                || (message.contains("重新") && message.contains("生成"));
    }

    /** 用户目标是分镜「提示词」而非「剧本」。 */
    private boolean isShotPromptTarget(String message) {
        if (message == null || message.isBlank()) return false;
        if (message.contains("提示词") || message.contains("prompt") || message.contains("Prompt")) {
            return true;
        }
        // 有分镜/镜头动作，但明确点名「剧本」时不算提示词目标
        if (message.contains("剧本") || message.contains("条目")) {
            return false;
        }
        return message.contains("分镜") || message.contains("镜头");
    }

    private boolean isDeleteScriptIntent(String message) {
        if (message == null || message.isBlank()) return false;
        if (isDeleteShotIntent(message) || isDeleteChapterIntent(message)) return false;
        boolean hasDelete = message.contains("删除") || message.contains("删掉") || message.contains("去掉");
        return hasDelete && message.contains("剧本");
    }

    private boolean isFineGrainedAssetIntent(String message) {
        if (message == null || message.isBlank()) return false;
        if (message.contains("分镜")) return false;
        if (message.contains("提取") || message.contains("抽取")) return false;
        // 「重新生成林浅的生图提示词」常不带「资产」二字，生图提示词本身即资产操作信号
        boolean mentionsAsset = message.contains("资产")
                || message.contains("人物")
                || message.contains("角色")
                || message.contains("场景")
                || message.contains("道具")
                || message.contains("生图");
        if (!mentionsAsset) return false;
        return message.contains("删除")
                || message.contains("删掉")
                || message.contains("新增")
                || message.contains("添加")
                || message.contains("创建")
                || message.contains("重写")
                || message.contains("改写")
                || message.contains("修改")
                || message.contains("更新")
                || message.contains("重新生成")
                || message.contains("重生")
                || (message.contains("生图") && (message.contains("生成") || message.contains("重写") || message.contains("优化")));
    }

    private ItemOpResult executeScriptOps(
            Long projectId,
            String message,
            List<Long> selectedShotIds,
            String focus,
            Long focusId,
            Consumer<String> statusUpdater,
            boolean confirmed
    ) {
        List<ScriptEntry> scripts = scriptEntryRepository.findByProjectIdOrderByOrderIndexAsc(projectId);
        if (scripts.isEmpty()) {
            return new ItemOpResult("暂无剧本，请先生成剧本。", false, false, List.of());
        }

        List<Integer> nums = new ArrayList<>(extractScriptNumbers(message, scripts.size()));
        if (nums.isEmpty() && selectedShotIds != null && !selectedShotIds.isEmpty()
                && (message.contains("这些") || message.contains("选中") || message.contains("勾选")
                || message.contains("这条") || message.contains("这些剧本"))) {
            nums = new ArrayList<>(scriptNumbersFromSelectedShots(projectId, selectedShotIds));
        }
        if (nums.isEmpty() && "script".equals(focus) && focusId != null) {
            for (ScriptEntry s : scripts) {
                if (focusId.equals(s.getId())) {
                    nums.add(s.getOrderIndex() + 1);
                    break;
                }
            }
        }
        if (nums.isEmpty() && (message.contains("第一") || message.contains("首条") || message.contains("第一条"))) {
            nums = new ArrayList<>(List.of(1));
        }
        if (nums.isEmpty() && message.contains("最后") && !scripts.isEmpty()) {
            nums = new ArrayList<>(List.of(scripts.size()));
        }
        if (nums.isEmpty()) {
            return new ItemOpResult(
                    "请指定剧本编号，例如「重写第 1 条剧本」或「删除第 2 条剧本」。也可勾选分镜后说「重写这些剧本」。",
                    false, false, List.of());
        }

        List<String> applied = new ArrayList<>();
        List<Integer> done = new ArrayList<>();
        boolean delete = isDeleteScriptIntent(message);

        if (delete) {
            String summary = "删除剧本 "
                    + nums.stream().sorted().map(n -> "#" + n).collect(Collectors.joining("、"))
                    + "（及其关联分镜）";
            if (!confirmed) {
                return ItemOpResult.pending("delete_scripts", summary);
            }
            workspaceService.createManualSnapshot(projectId,
                    "删除剧本 " + nums.stream().map(n -> "#" + n).collect(Collectors.joining("、")) + " 前");
            List<Integer> descending = new ArrayList<>(nums);
            descending.sort((a, b) -> Integer.compare(b, a));
            for (Integer num : descending) {
                scripts = scriptEntryRepository.findByProjectIdOrderByOrderIndexAsc(projectId);
                ScriptEntry target = scripts.stream()
                        .filter(s -> s.getOrderIndex() + 1 == num)
                        .findFirst()
                        .orElse(null);
                if (target == null) continue;
                if (statusUpdater != null) statusUpdater.accept("正在删除剧本 #" + num + "…");
                workspaceService.deleteScript(projectId, target.getId());
                applied.add("delete_script_" + num);
                done.add(num);
            }
            if (done.isEmpty()) {
                return new ItemOpResult("未找到要删除的剧本条目。", false, false, List.of());
            }
            done.sort(Integer::compareTo);
            int remain = scriptEntryRepository.findByProjectIdOrderByOrderIndexAsc(projectId).size();
            return new ItemOpResult(
                    "已删除剧本 " + done.stream().map(n -> "#" + n).collect(Collectors.joining("、"))
                            + "，当前剩余 " + remain + " 条。可在「版本快照」回滚。",
                    true, false, applied);
        }

        for (Integer num : nums) {
            ScriptEntry target = scripts.stream()
                    .filter(s -> s.getOrderIndex() + 1 == num)
                    .findFirst()
                    .orElse(null);
            if (target == null) continue;
            if (statusUpdater != null) statusUpdater.accept("正在重写剧本 #" + num + "…");
            workspaceService.regenerateScript(projectId, target.getId(), message);
            applied.add("rewrite_script_" + num);
            done.add(num);
        }
        if (done.isEmpty()) {
            return new ItemOpResult("未找到要重写的剧本条目。", false, false, List.of());
        }
        return new ItemOpResult(
                "已重写剧本 " + done.stream().map(n -> "#" + n).collect(Collectors.joining("、"))
                        + "，请在工作台剧本列查看。",
                true, false, applied);
    }

    private List<Integer> scriptNumbersFromSelectedShots(Long projectId, List<Long> selectedShotIds) {
        List<Integer> nums = new ArrayList<>();
        List<Shot> allShots = shotRepository.findByProjectIdWithRefs(projectId);
        List<ScriptEntry> scripts = scriptEntryRepository.findByProjectIdOrderByOrderIndexAsc(projectId);
        for (Shot shot : allShots) {
            if (!selectedShotIds.contains(shot.getId()) || shot.getScriptId() == null) continue;
            scripts.stream()
                    .filter(s -> s.getId().equals(shot.getScriptId()))
                    .findFirst()
                    .ifPresent(s -> addShotNumber(nums, s.getOrderIndex() + 1));
        }
        return nums;
    }

    private List<Integer> extractScriptNumbers(String message, int total) {
        // 复用分镜编号解析（第N条/第N个）
        List<Integer> nums = extractShotNumbers(message, total);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("剧本\\s*#?\\s*(\\d+)")
                .matcher(message);
        while (m.find()) {
            try {
                addShotNumber(nums, Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return nums;
    }

    private ItemOpResult executeAssetOps(
            Long projectId,
            String message,
            String focus,
            Long focusId,
            Consumer<String> statusUpdater,
            boolean confirmed
    ) {
        List<Asset> assets = assetRepository.findByProjectIdOrderByTypeAscNameAsc(projectId);
        boolean isDelete = message.contains("删除") || message.contains("删掉") || message.contains("去掉");
        boolean isCreate = message.contains("新增") || message.contains("添加") || message.contains("创建");
        boolean isRegenPrompt = message.contains("生图")
                || (message.contains("提示词") && (message.contains("重新生成") || message.contains("重生") || message.contains("重写")));
        // 「重新生成生图提示词」含「重写」语义时应走 regen，不要整资产 rewrite
        boolean isRewrite = !isRegenPrompt && (message.contains("重写") || message.contains("改写") || message.contains("修改")
                || message.contains("更新") || message.contains("优化"));

        if (isCreate) {
            return executeCreateAsset(projectId, message, statusUpdater);
        }

        Asset target = resolveAssetTarget(assets, message);
        if (target == null && "asset".equals(focus) && focusId != null) {
            target = assets.stream().filter(a -> focusId.equals(a.getId())).findFirst().orElse(null);
        }
        if (target == null) {
            if (assets.isEmpty()) {
                return new ItemOpResult("暂无资产，请先提取资产或新增。", false, false, List.of());
            }
            String names = assets.stream().limit(8).map(Asset::getName).collect(Collectors.joining("、"));
            return new ItemOpResult(
                    "请指明资产名称，例如「删除资产林浅」或「重新生成林浅的生图提示词」。当前有：" + names,
                    false, false, List.of());
        }

        if (isDelete) {
            String summary = "删除资产「" + target.getName() + "」";
            if (!confirmed) {
                return ItemOpResult.pending("delete_asset", summary);
            }
            if (statusUpdater != null) statusUpdater.accept("正在删除资产「" + target.getName() + "」…");
            workspaceService.createManualSnapshot(projectId, "删除资产「" + target.getName() + "」前");
            workspaceService.deleteAsset(projectId, target.getId());
            return new ItemOpResult(
                    "已删除资产「" + target.getName() + "」。可在「版本快照」回滚。",
                    false, true, List.of("delete_asset_" + target.getId()));
        }

        if (isRegenPrompt && !isRewrite) {
            if (statusUpdater != null) statusUpdater.accept("正在重新生成「" + target.getName() + "」生图提示词…");
            workspaceService.regenerateAssetPrompt(projectId, target.getId());
            return new ItemOpResult(
                    "已重新生成资产「" + target.getName() + "」的生图提示词。",
                    false, true, List.of("regen_asset_prompt_" + target.getId()));
        }

        // 默认：重写介绍+提示词
        if (statusUpdater != null) statusUpdater.accept("正在重写资产「" + target.getName() + "」…");
        workspaceService.rewriteAsset(projectId, target.getId(), message);
        return new ItemOpResult(
                "已重写资产「" + target.getName() + "」的介绍与生图提示词。",
                false, true, List.of("rewrite_asset_" + target.getId()));
    }

    private ItemOpResult executeCreateAsset(Long projectId, String message, Consumer<String> statusUpdater) {
        AssetType type = AssetType.character;
        if (message.contains("场景")) type = AssetType.scene;
        else if (message.contains("道具")) type = AssetType.prop;
        else if (message.contains("人物") || message.contains("角色")) type = AssetType.character;

        String name = extractNewAssetName(message);
        if (name == null || name.isBlank()) {
            return new ItemOpResult("请说明要新增的资产名称，例如「新增角色林浅」。", false, false, List.of());
        }
        if (statusUpdater != null) statusUpdater.accept("正在新增资产「" + name + "」…");
        WorkspaceDtos.AssetCreateRequest req = new WorkspaceDtos.AssetCreateRequest();
        req.setType(type);
        req.setName(name);
        req.setDescription(extractAssetDescriptionHint(message));
        req.setImagePrompt("");
        WorkspaceDtos.AssetResponse created = workspaceService.createAsset(projectId, req);
        // 若用户没给介绍，直接走一次 rewrite 补全
        if (created.getDescription() == null || created.getDescription().isBlank()
                || created.getImagePrompt() == null || created.getImagePrompt().isBlank()) {
            workspaceService.rewriteAsset(projectId, created.getId(), message);
        }
        return new ItemOpResult(
                "已新增资产「" + name + "」，请在资产库查看。",
                false, true, List.of("create_asset_" + created.getId()));
    }

    private Asset resolveAssetTarget(List<Asset> assets, String message) {
        if (assets == null || assets.isEmpty()) return null;
        // 长名优先匹配，避免短名误伤
        List<Asset> sorted = new ArrayList<>(assets);
        sorted.sort((a, b) -> Integer.compare(
                b.getName() == null ? 0 : b.getName().length(),
                a.getName() == null ? 0 : a.getName().length()));
        for (Asset asset : sorted) {
            if (asset.getName() != null && !asset.getName().isBlank() && message.contains(asset.getName())) {
                return asset;
            }
        }
        return null;
    }

    private String extractNewAssetName(String message) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:新增|添加|创建)\\s*(?:一个)?\\s*(?:资产|人物|角色|场景|道具)?\\s*[「\"“]?([^」\"”\\s，,。；;]+)[」\"”]?"
        ).matcher(message);
        if (m.find()) {
            String name = m.group(1).trim();
            if (!name.isEmpty() && !List.of("资产", "人物", "角色", "场景", "道具").contains(name)) {
                return name;
            }
        }
        return null;
    }

    private String extractAssetDescriptionHint(String message) {
        int idx = message.indexOf("介绍");
        if (idx >= 0 && idx + 2 < message.length()) {
            return message.substring(idx + 2).replaceFirst("^[：:\\s]+", "").trim();
        }
        return "";
    }

    private boolean isGenerateShotsIntent(String message) {
        String m = message.toLowerCase();
        // 「第N个分镜的剧本重新生成」不是提示词生成
        if (m.contains("剧本") && !isShotPromptTarget(message)) {
            return false;
        }
        boolean hasShot = m.contains("分镜");
        boolean hasPrompt = m.contains("提示词") || m.contains("prompt");
        boolean hasAction = m.contains("生成") || m.contains("生产") || m.contains("写")
                || m.contains("创作") || m.contains("划分") || m.contains("全部");
        if (hasShot && hasAction) {
            return true;
        }
        if (hasPrompt && hasAction && (hasShot || m.contains("视频"))) {
            return true;
        }
        return m.contains("生成分镜") || m.contains("划分分镜");
    }

    private boolean isRegenerateShotIntent(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("重新生成")
                || message.contains("重新写")
                || message.contains("重写")
                || message.contains("再生成")
                || message.contains("重新给我生成")
                || message.contains("再给我生成")
                || message.contains("重新出")
                || (message.contains("重新") && message.contains("生成"))
                || (message.contains("再") && message.contains("生成")
                    && (message.contains("分镜") || message.contains("提示词")));
    }

    private List<Integer> extractShotNumbers(String message, int totalShots) {
        List<Integer> result = new ArrayList<>();

        if (java.util.regex.Pattern.compile("最后(?:一个|1个|那个|的分镜)").matcher(message).find()
                && totalShots > 0) {
            addShotNumber(result, totalShots);
        }

        java.util.regex.Matcher ordinal = java.util.regex.Pattern
                .compile("第\\s*([一二三四五六七八九十两\\d]+)\\s*(?:个|条|镜)?")
                .matcher(message);
        while (ordinal.find()) {
            if (ordinal.start() > 0 && message.charAt(ordinal.start() - 1) == '后') {
                continue;
            }
            // 跳过「第N章」，避免章节号被当成分镜号
            int end = ordinal.end();
            if (end < message.length() && message.charAt(end) == '章') {
                continue;
            }
            Integer n = parseShotNumberToken(ordinal.group(1));
            addShotNumber(result, n);
        }

        java.util.regex.Matcher multi = java.util.regex.Pattern
                .compile("第\\s*([\\d一二三四五六七八九十两，,、和及至到~\\-\\s]+)\\s*个?\\s*分镜")
                .matcher(message);
        if (multi.find()) {
            for (Integer n : parseShotNumberList(multi.group(1))) {
                addShotNumber(result, n);
            }
        }

        java.util.regex.Matcher hash = java.util.regex.Pattern
                .compile("分镜\\s*#?\\s*(\\d+)")
                .matcher(message);
        while (hash.find()) {
            try {
                addShotNumber(result, Integer.parseInt(hash.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }

        result.sort(Integer::compareTo);
        return result;
    }

    private void addShotNumber(List<Integer> result, Integer n) {
        if (n != null && n >= 1 && n <= 100 && !result.contains(n)) {
            result.add(n);
        }
    }

    private List<Integer> parseShotNumberList(String raw) {
        List<Integer> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String part : raw.split("[，,、和及至到~\\-\\s]+")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            Integer n = parseShotNumberToken(token);
            if (n != null && n >= 1 && n <= 100 && !result.contains(n)) {
                result.add(n);
            }
        }
        return result;
    }

    private Integer parseShotNumberToken(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException ignored) {
            return parseChineseNumber(token);
        }
    }

    private boolean isWriteSourceIntent(String message) {
        String m = message.toLowerCase();
        if (isGenerateShotsIntent(message) || isGenerateScriptIntent(message) || isExtractAssetsIntent(message)) {
            return false;
        }
        // 「根据原文生成剧本」含「原文+生成」，但目标是剧本不是写小说
        if (message.contains("剧本")) {
            return false;
        }
        if (isDeleteChapterIntent(message)) {
            return true;
        }
        boolean hasWrite = m.contains("写") || m.contains("创作") || m.contains("生成") || m.contains("填充")
                || m.contains("撰写") || m.contains("重写") || m.contains("改写");
        boolean hasTarget = m.contains("原文") || m.contains("小说") || m.contains("章节") || m.contains("章");
        return hasWrite && hasTarget;
    }

    private boolean isDeleteChapterIntent(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        boolean hasDelete = message.contains("删除") || message.contains("删掉") || message.contains("去掉")
                || message.contains("移除");
        boolean hasChapter = message.contains("章");
        return hasDelete && hasChapter && extractTargetChapter(message) != null;
    }

    /** 重写指定章节（不是整本重来） */
    private boolean isRewriteChapterIntent(String message) {
        if (isDeleteChapterIntent(message)) {
            return false;
        }
        return message.contains("重写")
                || message.contains("重新写")
                || message.contains("改写")
                || message.contains("重写一下")
                || message.contains("重新生成");
    }

    /** 追加/续写意图：不应整本重写 */
    private boolean isAppendSourceIntent(String message) {
        if (isRewriteChapterIntent(message)) {
            return false;
        }
        return message.contains("追加")
                || message.contains("续写")
                || message.contains("再写")
                || message.contains("接着写")
                || message.contains("继续写")
                || message.contains("写下一章")
                || message.contains("下一章")
                || message.contains("接在后面")
                || message.contains("追加到最后");
    }

    /** 解析「第N章 / 第一章」目标章节号 */
    private Integer extractTargetChapter(String message) {
        java.util.regex.Matcher digit = java.util.regex.Pattern
                .compile("第\\s*(\\d+)\\s*章")
                .matcher(message);
        if (digit.find()) {
            try {
                int n = Integer.parseInt(digit.group(1));
                if (n >= 1 && n <= 100) {
                    return n;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        java.util.regex.Matcher cn = java.util.regex.Pattern
                .compile("第\\s*([一二三四五六七八九十两]+)\\s*章")
                .matcher(message);
        if (cn.find()) {
            Integer n = parseChineseNumber(cn.group(1));
            if (n != null && n >= 1 && n <= 100) {
                return n;
            }
        }
        return null;
    }

    private Integer parseChineseNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        if ("两".equals(s)) {
            return 2;
        }
        java.util.Map<Character, Integer> map = new java.util.HashMap<>();
        map.put('零', 0);
        map.put('一', 1);
        map.put('二', 2);
        map.put('三', 3);
        map.put('四', 4);
        map.put('五', 5);
        map.put('六', 6);
        map.put('七', 7);
        map.put('八', 8);
        map.put('九', 9);
        map.put('十', 10);
        if (s.length() == 1) {
            return map.get(s.charAt(0));
        }
        if (s.startsWith("十")) {
            if (s.length() == 1) {
                return 10;
            }
            Integer ones = map.get(s.charAt(1));
            return ones == null ? 10 : 10 + ones;
        }
        if (s.endsWith("十") && s.length() == 2) {
            Integer tens = map.get(s.charAt(0));
            return tens == null ? null : tens * 10;
        }
        if (s.length() == 3 && s.charAt(1) == '十') {
            Integer tens = map.get(s.charAt(0));
            Integer ones = map.get(s.charAt(2));
            if (tens == null || ones == null) {
                return null;
            }
            return tens * 10 + ones;
        }
        return null;
    }

    private String toChineseNumber(int n) {
        String[] simple = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
                "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十"};
        if (n >= 1 && n < simple.length) {
            return simple[n];
        }
        return String.valueOf(n);
    }

    private int detectMaxChapter(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("第\\s*(\\d+)\\s*章")
                .matcher(content);
        int max = 0;
        while (matcher.find()) {
            try {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        java.util.regex.Matcher cn = java.util.regex.Pattern
                .compile("第\\s*([一二三四五六七八九十两]+)\\s*章")
                .matcher(content);
        while (cn.find()) {
            Integer n = parseChineseNumber(cn.group(1));
            if (n != null) {
                max = Math.max(max, n);
            }
        }
        return max;
    }

    /** 解析「前五章 / 前 N 章」：表示创作到前 N 章为止 */
    private Integer extractPrefixChapterTarget(String message) {
        java.util.regex.Matcher digit = java.util.regex.Pattern
                .compile("前\\s*(\\d+)\\s*章")
                .matcher(message);
        if (digit.find()) {
            try {
                int n = Integer.parseInt(digit.group(1));
                if (n >= 1 && n <= 100) {
                    return n;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        java.util.regex.Matcher cn = java.util.regex.Pattern
                .compile("前\\s*([一二三四五六七八九十两]+)\\s*章")
                .matcher(message);
        if (cn.find()) {
            Integer n = parseChineseNumber(cn.group(1));
            if (n != null && n >= 1 && n <= 100) {
                return n;
            }
        }
        return null;
    }

    /**
     * 解析「写 N 章 / N 个章节」数量；刻意避开「第 N 章」以免把目标章号当成总章数。
     */
    private int extractChapterCount(String message, int defaultCount) {
        Integer prefixTarget = extractPrefixChapterTarget(message);
        if (prefixTarget != null) {
            return prefixTarget;
        }
        String[] patterns = {
                "(\\d+)\\s*个\\s*章",
                "(\\d+)\\s*章节",
                "共\\s*(\\d+)\\s*章",
                "(?:再写|续写|追加|写|生成|创作)\\s*(\\d+)\\s*章"
        };
        for (String pattern : patterns) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(message);
            if (matcher.find()) {
                try {
                    int start = matcher.start(1);
                    if (start >= 1 && message.charAt(start - 1) == '第') {
                        continue;
                    }
                    int n = Integer.parseInt(matcher.group(1));
                    return Math.min(Math.max(n, 1), 20);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return defaultCount;
    }

    private void saveSourceContent(Long projectId, String content, boolean append) {
        SourceText source = sourceTextRepository.findByProjectId(projectId)
                .orElseThrow(() -> new BusinessException("原文不存在"));
        if (append) {
            String old = source.getContent() == null ? "" : source.getContent();
            source.setContent(old.isBlank() ? content : old + "\n\n" + content);
        } else {
            source.setContent(content);
        }
        sourceTextRepository.save(source);
    }

    private String buildContext(Long projectId, AssistantDtos.ChatRequest req) {
        String focus = req != null ? req.getFocus() : null;
        Long focusId = req != null ? req.getFocusId() : null;
        List<Long> selectedShotIds = req != null && req.getSelectedShotIds() != null
                ? req.getSelectedShotIds()
                : List.of();

        StringBuilder sb = new StringBuilder();
        sourceTextRepository.findByProjectId(projectId).ifPresent(s ->
                sb.append("原文摘要：").append(truncate(s.getContent(), 800)).append("\n"));

        var scripts = scriptEntryRepository.findByProjectIdOrderByOrderIndexAsc(projectId);
        var assets = assetRepository.findByProjectIdOrderByTypeAscNameAsc(projectId);
        var shots = shotRepository.findByProjectIdWithRefs(projectId);
        sb.append("剧本条目数：").append(scripts.size()).append("\n");
        sb.append("资产数：").append(assets.size()).append("\n");
        sb.append("分镜数：").append(shots.size()).append("\n");

        // 近期对话，帮助多轮「继续 / 改刚才那个」
        List<AssistantMessage> recent = assistantMessageRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
        if (!recent.isEmpty()) {
            int from = Math.max(0, recent.size() - 6);
            sb.append("近期对话：\n");
            for (int i = from; i < recent.size(); i++) {
                AssistantMessage m = recent.get(i);
                String role = "assistant".equals(m.getRole()) ? "助手" : "用户";
                sb.append("- ").append(role).append("：")
                        .append(truncate(m.getContent(), 120)).append("\n");
            }
        }

        if (!selectedShotIds.isEmpty()) {
            sb.append("工作台已选分镜：");
            List<String> labels = new ArrayList<>();
            for (Shot shot : shots) {
                if (selectedShotIds.contains(shot.getId())) {
                    labels.add("#" + (shot.getOrderIndex() + 1));
                }
            }
            sb.append(String.join("、", labels)).append("\n");
            for (Shot shot : shots) {
                if (!selectedShotIds.contains(shot.getId())) {
                    continue;
                }
                appendShotDetail(sb, shot);
            }
        } else if ("shot".equals(focus) && focusId != null) {
            shots.stream().filter(s -> focusId.equals(s.getId())).findFirst()
                    .ifPresent(shot -> appendShotDetail(sb, shot));
        }

        if ("asset".equals(focus) && focusId != null) {
            assets.stream().filter(a -> focusId.equals(a.getId())).findFirst().ifPresent(asset -> {
                sb.append("当前关注资产：[").append(asset.getType()).append("] ")
                        .append(asset.getName()).append("\n");
                sb.append("介绍：").append(truncate(asset.getDescription(), 160)).append("\n");
                sb.append("生图提示词：").append(truncate(asset.getImagePrompt(), 200)).append("\n");
            });
        }

        if ("script".equals(focus) && focusId != null) {
            scripts.stream().filter(s -> focusId.equals(s.getId())).findFirst().ifPresent(script -> {
                sb.append("当前关注剧本 #").append(script.getOrderIndex() + 1).append("\n");
                sb.append("动作：").append(truncate(script.getAction(), 160)).append("\n");
                sb.append("台词：").append(truncate(script.getDialogue(), 120)).append("\n");
                sb.append("镜头：").append(truncate(script.getShot(), 120)).append("\n");
            });
        }

        return sb.toString();
    }

    private void appendShotDetail(StringBuilder sb, Shot shot) {
        sb.append("当前分镜 #").append(shot.getOrderIndex() + 1).append("\n");
        sb.append("视频提示词：").append(truncate(shot.getVideoPrompt(), 280)).append("\n");
        if (shot.getReferencePrompts() != null && !shot.getReferencePrompts().isEmpty()) {
            shot.getReferencePrompts().stream().limit(2).forEach(ref ->
                    sb.append("参考图(").append(ref.getType()).append(")：")
                            .append(truncate(ref.getPrompt(), 120)).append("\n"));
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private AssistantDtos.MessageResponse toResponse(AssistantMessage msg) {
        AssistantDtos.MessageResponse resp = new AssistantDtos.MessageResponse();
        resp.setId(msg.getId());
        resp.setRole(msg.getRole());
        resp.setContent(msg.getContent());
        resp.setCreatedAt(msg.getCreatedAt() != null
                ? msg.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                : "");
        return resp;
    }
}
