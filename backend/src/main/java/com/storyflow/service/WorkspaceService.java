package com.storyflow.service;

import com.storyflow.ai.LlmGateway;
import com.storyflow.ai.ProjectSettingsSupport;
import com.storyflow.ai.PromptComposeSupport;
import com.storyflow.common.BusinessException;
import com.storyflow.dto.ProjectDtos;
import com.storyflow.dto.WorkspaceDtos;
import com.storyflow.entity.*;
import com.storyflow.repository.*;
import com.storyflow.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class WorkspaceService {

    private static final int SNAPSHOT_KEEP_LIMIT = 30;

    private final ProjectService projectService;
    private final SourceTextRepository sourceTextRepository;
    private final ScriptEntryRepository scriptEntryRepository;
    private final AssetRepository assetRepository;
    private final ShotRepository shotRepository;
    private final ProjectSnapshotRepository projectSnapshotRepository;
    private final LlmGateway llmGateway;
    private final PromptComposeSupport promptCompose;
    private final ProjectSettingsSupport projectSettingsSupport;
    private final JsonUtil jsonUtil;
    private final AiUsageLogService aiUsageLogService;
    private final QuotaService quotaService;
    private final MediaVaultService mediaVaultService;
    private final TransactionTemplate tx;

    /** 剧本生成流式回调：按原文分块落库后逐步推送，供 SSE / 助手状态使用。 */
    public interface ScriptStreamListener {
        default void onStatus(String text) {}
        default void onCleared() {}
        default void onScripts(List<WorkspaceDtos.ScriptResponse> batch, int chunkIndex, int chunkTotal) {}
        /** 剧本生成末尾提取资产时逐步推送（人物→场景→道具）。 */
        default void onAssets(
                String type, List<WorkspaceDtos.AssetResponse> batch, int stepIndex, int stepTotal) {}
        default void onAssetsCleared() {}
        default void onDone(
                List<WorkspaceDtos.ScriptResponse> scripts,
                List<WorkspaceDtos.ShotResponse> shots,
                List<WorkspaceDtos.AssetResponse> assets) {}
    }

    /** 资产提取流式回调：按类型落库后逐步推送。 */
    public interface AssetExtractListener {
        default void onStatus(String text) {}
        default void onCleared() {}
        default void onAssets(
                String type, List<WorkspaceDtos.AssetResponse> batch, int stepIndex, int stepTotal) {}
        default void onDone(List<WorkspaceDtos.AssetResponse> assets) {}
    }

    public WorkspaceService(ProjectService projectService, SourceTextRepository sourceTextRepository,
                            ScriptEntryRepository scriptEntryRepository, AssetRepository assetRepository,
                            ShotRepository shotRepository, ProjectSnapshotRepository projectSnapshotRepository,
                            LlmGateway llmGateway, PromptComposeSupport promptCompose,
                            ProjectSettingsSupport projectSettingsSupport, JsonUtil jsonUtil,
                            AiUsageLogService aiUsageLogService, QuotaService quotaService,
                            MediaVaultService mediaVaultService,
                            PlatformTransactionManager transactionManager) {
        this.projectService = projectService;
        this.sourceTextRepository = sourceTextRepository;
        this.scriptEntryRepository = scriptEntryRepository;
        this.assetRepository = assetRepository;
        this.shotRepository = shotRepository;
        this.projectSnapshotRepository = projectSnapshotRepository;
        this.llmGateway = llmGateway;
        this.promptCompose = promptCompose;
        this.projectSettingsSupport = projectSettingsSupport;
        this.jsonUtil = jsonUtil;
        this.aiUsageLogService = aiUsageLogService;
        this.quotaService = quotaService;
        this.mediaVaultService = mediaVaultService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public WorkspaceDtos.SourceTextResponse getSource(Long projectId) {
        projectService.getProject(projectId);
        SourceText source = sourceTextRepository.findByProjectId(projectId)
                .orElseThrow(() -> new BusinessException("原文不存在"));
        WorkspaceDtos.SourceTextResponse resp = new WorkspaceDtos.SourceTextResponse();
        resp.setContent(source.getContent());
        return resp;
    }

    @Transactional
    public WorkspaceDtos.SourceTextResponse updateSource(Long projectId, WorkspaceDtos.SourceTextRequest req) {
        projectService.requireWritableProject(projectId);
        SourceText source = sourceTextRepository.findByProjectId(projectId)
                .orElseThrow(() -> new BusinessException("原文不存在"));
        source.setContent(req.getContent() != null ? req.getContent() : "");
        sourceTextRepository.save(source);
        return getSource(projectId);
    }

    public List<WorkspaceDtos.ScriptResponse> listScripts(Long projectId) {
        projectService.getProject(projectId);
        return scriptEntryRepository.findByProjectIdOrderByOrderIndexAsc(projectId).stream()
                .map(this::toScriptResponse)
                .toList();
    }

    public List<WorkspaceDtos.ScriptResponse> generateScript(Long projectId) {
        List<WorkspaceDtos.ScriptResponse> result = new ArrayList<>();
        generateScriptStreaming(projectId, new ScriptStreamListener() {
            @Override
            public void onDone(
                    List<WorkspaceDtos.ScriptResponse> scripts,
                    List<WorkspaceDtos.ShotResponse> shots,
                    List<WorkspaceDtos.AssetResponse> assets) {
                result.addAll(scripts);
            }
        });
        return result;
    }

    /**
     * 按原文分块生成剧本：每块完成后立即落库并回调，便于 SSE 实时展示。
     * 不在外层持有长事务（LLM 调用在事务外）。
     */
    public void generateScriptStreaming(Long projectId, ScriptStreamListener listener) {
        ScriptStreamListener sink = listener != null ? listener : new ScriptStreamListener() {};
        Project project = projectService.requireWritableProject(projectId);
        quotaService.checkAiUsage(AiUsageLogService.TYPE_SCRIPT_GENERATE);
        SourceText source = sourceTextRepository.findByProjectId(projectId)
                .orElseThrow(() -> new BusinessException("请先填写原文"));
        if (source.getContent() == null || source.getContent().isBlank()) {
            throw new BusinessException("原文不能为空");
        }

        try {
            List<String> chunks = splitText(source.getContent(), 1200);
            int chunkTotal = chunks.size();

            sink.onStatus("准备生成剧本…");
            tx.executeWithoutResult(status -> {
                createSnapshot(projectId, "before_script_generate", "生成剧本前快照");
                scriptEntryRepository.deleteByProjectId(projectId);
                // 清空旧分镜，流式阶段才能以「仅剧本」行实时展示
                shotRepository.deleteByProjectId(projectId);
            });
            sink.onCleared();

            int orderIndex = 0;
            int savedCount = 0;
            for (int i = 0; i < chunkTotal; i++) {
                int chunkIndex = i + 1;
                sink.onStatus("正在生成第 " + chunkIndex + "/" + chunkTotal + " 段剧本…");
                List<LlmGateway.ScriptDto> batch = llmGateway.generateScript(chunks.get(i), project.getStylePreset());
                final int startIndex = orderIndex;
                List<WorkspaceDtos.ScriptResponse> saved = tx.execute(status ->
                        saveScriptBatch(projectId, batch, startIndex));
                if (saved == null) {
                    saved = List.of();
                }
                orderIndex += saved.size();
                savedCount += saved.size();
                sink.onScripts(saved, chunkIndex, chunkTotal);
                sink.onStatus("已写入 " + savedCount + " 条剧本（" + chunkIndex + "/" + chunkTotal + "）");
            }

            sink.onStatus("正在提取资产…");
            try {
                // LLM 在事务外；按人物→场景→道具逐步落库并回调
                replaceAssetsFromScriptStreaming(projectId, project.getStylePreset(), new AssetExtractListener() {
                    @Override
                    public void onStatus(String text) {
                        sink.onStatus(text);
                    }

                    @Override
                    public void onCleared() {
                        sink.onAssetsCleared();
                    }

                    @Override
                    public void onAssets(
                            String type, List<WorkspaceDtos.AssetResponse> batch, int stepIndex, int stepTotal) {
                        sink.onAssets(type, batch, stepIndex, stepTotal);
                    }
                });
                aiUsageLogService.logSuccess(
                        projectId,
                        AiUsageLogService.TYPE_ASSET_EXTRACT,
                        "生成剧本时自动提取资产",
                        null
                );
            } catch (Exception assetEx) {
                aiUsageLogService.logFailure(
                        projectId,
                        AiUsageLogService.TYPE_ASSET_EXTRACT,
                        "生成剧本后提取资产失败",
                        assetEx.getMessage()
                );
            }

            sink.onStatus("正在创建分镜占位…");
            tx.executeWithoutResult(status -> recreateShotPlaceholders(projectId));
            aiUsageLogService.logSuccess(
                    projectId,
                    AiUsageLogService.TYPE_SCRIPT_GENERATE,
                    "生成剧本，共 " + savedCount + " 条",
                    null
            );
            sink.onDone(listScripts(projectId), listShots(projectId), listAssets(projectId));
        } catch (Exception ex) {
            aiUsageLogService.logFailure(
                    projectId,
                    AiUsageLogService.TYPE_SCRIPT_GENERATE,
                    "生成剧本失败",
                    ex.getMessage()
            );
            throw ex;
        }
    }

    private List<WorkspaceDtos.ScriptResponse> saveScriptBatch(
            Long projectId, List<LlmGateway.ScriptDto> batch, int startIndex) {
        List<WorkspaceDtos.ScriptResponse> saved = new ArrayList<>();
        int index = startIndex;
        for (LlmGateway.ScriptDto dto : batch) {
            ScriptEntry entry = new ScriptEntry();
            entry.setProjectId(projectId);
            entry.setTimeLabel(dto.time());
            entry.setLocation(dto.location());
            entry.setCharactersJson(jsonUtil.toJson(dto.characters() != null ? dto.characters() : List.of()));
            entry.setAction(dto.action());
            entry.setDialogue(dto.dialogue());
            entry.setShot(dto.shot());
            entry.setOrderIndex(index++);
            scriptEntryRepository.save(entry);
            saved.add(toScriptResponse(entry));
        }
        return saved;
    }

    @Transactional
    public WorkspaceDtos.ScriptResponse updateScript(Long projectId, Long scriptId,
                                                       WorkspaceDtos.ScriptUpdateRequest req) {
        projectService.requireWritableProject(projectId);
        ScriptEntry entry = scriptEntryRepository.findById(scriptId)
                .filter(s -> s.getProjectId().equals(projectId))
                .orElseThrow(() -> new BusinessException("剧本条目不存在"));
        if (req.getTime() != null) entry.setTimeLabel(req.getTime());
        if (req.getLocation() != null) entry.setLocation(req.getLocation());
        if (req.getCharacters() != null) entry.setCharactersJson(jsonUtil.toJson(req.getCharacters()));
        if (req.getAction() != null) entry.setAction(req.getAction());
        if (req.getDialogue() != null) entry.setDialogue(req.getDialogue());
        if (req.getShot() != null) entry.setShot(req.getShot());
        scriptEntryRepository.save(entry);
        relinkShotsForScript(projectId, entry);
        return toScriptResponse(entry);
    }

    /** 按指令重写单条剧本（细粒度） */
    @Transactional
    public WorkspaceDtos.ScriptResponse regenerateScript(Long projectId, Long scriptId, String instruction) {
        Project project = projectService.requireWritableProject(projectId);
        ScriptEntry entry = scriptEntryRepository.findById(scriptId)
                .filter(s -> s.getProjectId().equals(projectId))
                .orElseThrow(() -> new BusinessException("剧本条目不存在"));
        List<String> characters = jsonUtil.parseStringList(entry.getCharactersJson());
        LlmGateway.ScriptDto rewritten = llmGateway.rewriteScriptEntry(
                new LlmGateway.ScriptDto(
                        entry.getTimeLabel(),
                        entry.getLocation(),
                        characters,
                        entry.getAction(),
                        entry.getDialogue(),
                        entry.getShot()
                ),
                project.getStylePreset(),
                instruction
        );
        WorkspaceDtos.ScriptUpdateRequest req = new WorkspaceDtos.ScriptUpdateRequest();
        req.setTime(rewritten.time());
        req.setLocation(rewritten.location());
        req.setCharacters(rewritten.characters());
        req.setAction(rewritten.action());
        req.setDialogue(rewritten.dialogue());
        req.setShot(rewritten.shot());
        return updateScript(projectId, scriptId, req);
    }

    /** 删除单条剧本及其关联分镜 */
    @Transactional
    public void deleteScript(Long projectId, Long scriptId) {
        projectService.requireWritableProject(projectId);
        ScriptEntry entry = scriptEntryRepository.findById(scriptId)
                .filter(s -> s.getProjectId().equals(projectId))
                .orElseThrow(() -> new BusinessException("剧本条目不存在"));
        List<Shot> linked = shotRepository.findByProjectIdWithRefs(projectId).stream()
                .filter(s -> scriptId.equals(s.getScriptId()))
                .toList();
        for (Shot shot : linked) {
            deleteShot(projectId, shot.getId());
        }
        // 若仍存在（无关联分镜时），直接删剧本
        scriptEntryRepository.findById(scriptId).ifPresent(scriptEntryRepository::delete);
        List<ScriptEntry> remain = scriptEntryRepository.findByProjectIdOrderByOrderIndexAsc(projectId);
        for (int i = 0; i < remain.size(); i++) {
            ScriptEntry s = remain.get(i);
            if (s.getOrderIndex() != i) {
                s.setOrderIndex(i);
                scriptEntryRepository.save(s);
            }
        }
    }

    public List<WorkspaceDtos.AssetResponse> listAssets(Long projectId) {
        projectService.getProject(projectId);
        return assetRepository.findByProjectIdOrderByTypeAscNameAsc(projectId).stream()
                .map(this::toAssetResponse)
                .toList();
    }

    public List<WorkspaceDtos.AssetResponse> extractAssets(Long projectId) {
        List<WorkspaceDtos.AssetResponse> result = new ArrayList<>();
        extractAssetsStreaming(projectId, new AssetExtractListener() {
            @Override
            public void onDone(List<WorkspaceDtos.AssetResponse> assets) {
                result.addAll(assets);
            }
        });
        return result;
    }

    /**
     * 按人物→场景→道具逐步提取并落库，每步完成后回调，便于 SSE 实时刷新资产库。
     * LLM 在事务外；落库用短事务。
     */
    public void extractAssetsStreaming(Long projectId, AssetExtractListener listener) {
        AssetExtractListener sink = listener != null ? listener : new AssetExtractListener() {};
        Project project = projectService.requireWritableProject(projectId);
        quotaService.checkAiUsage(AiUsageLogService.TYPE_ASSET_EXTRACT);
        List<ScriptEntry> scripts = scriptEntryRepository.findByProjectIdOrderByOrderIndexAsc(projectId);
        if (scripts.isEmpty()) {
            throw new BusinessException("请先生成剧本");
        }
        try {
            int count = replaceAssetsFromScriptStreaming(projectId, project.getStylePreset(), sink);
            tx.executeWithoutResult(status -> {
                if (shotRepository.findByProjectIdWithRefs(projectId).isEmpty()) {
                    recreateShotPlaceholders(projectId);
                } else {
                    relinkAllShots(projectId);
                }
            });
            aiUsageLogService.logSuccess(
                    projectId,
                    AiUsageLogService.TYPE_ASSET_EXTRACT,
                    "提取资产，共 " + count + " 个",
                    null
            );
            sink.onDone(listAssets(projectId));
        } catch (Exception ex) {
            aiUsageLogService.logFailure(
                    projectId,
                    AiUsageLogService.TYPE_ASSET_EXTRACT,
                    "提取资产失败",
                    ex.getMessage()
            );
            throw ex;
        }
    }

    /** 从当前剧本提取资产并落库，返回资产数量。 */
    private int replaceAssetsFromScript(Long projectId, String stylePreset) {
        return replaceAssetsFromScriptStreaming(projectId, stylePreset, null);
    }

    private static final String[] ASSET_EXTRACT_STEPS = {"character", "scene", "prop"};
    private static final Map<String, String> ASSET_EXTRACT_STEP_LABEL = Map.of(
            "character", "人物",
            "scene", "场景",
            "prop", "道具"
    );

    /** LLM 在事务外，按类型逐步落库并回调。 */
    private int replaceAssetsFromScriptStreaming(
            Long projectId, String stylePreset, AssetExtractListener listener) {
        AssetExtractListener sink = listener != null ? listener : new AssetExtractListener() {};
        List<ScriptEntry> scripts = scriptEntryRepository.findByProjectIdOrderByOrderIndexAsc(projectId);
        String scriptJson = jsonUtil.toJson(scripts.stream().map(this::toAssetExtractSummary).toList());
        String novelText = sourceTextRepository.findByProjectId(projectId)
                .map(SourceText::getContent)
                .orElse("");

        sink.onStatus("准备提取资产…");
        List<List<LlmGateway.AssetDto>> batches = new ArrayList<>();
        int stepTotal = ASSET_EXTRACT_STEPS.length;
        for (int i = 0; i < stepTotal; i++) {
            String type = ASSET_EXTRACT_STEPS[i];
            String label = ASSET_EXTRACT_STEP_LABEL.getOrDefault(type, type);
            sink.onStatus("正在提取" + label + "（" + (i + 1) + "/" + stepTotal + "）…");
            batches.add(llmGateway.extractAssetsOfType(type, novelText, scriptJson, stylePreset));
        }

        // 全部模型调用成功后再原子替换；解析失败或超时不能先清空已有资产。
        List<List<WorkspaceDtos.AssetResponse>> savedBatches = tx.execute(status -> {
            assetRepository.deleteByProjectId(projectId);
            List<List<WorkspaceDtos.AssetResponse>> saved = new ArrayList<>();
            for (List<LlmGateway.AssetDto> batch : batches) {
                List<WorkspaceDtos.AssetResponse> out = new ArrayList<>();
                for (LlmGateway.AssetDto dto : batch) {
                    Asset asset = new Asset();
                    asset.setProjectId(projectId);
                    asset.setType(parseAssetType(dto.type()));
                    asset.setName(dto.name());
                    asset.setDescription(dto.description());
                    asset.setImagePrompt(dto.imagePrompt());
                    assetRepository.save(asset);
                    out.add(toAssetResponse(asset));
                }
                saved.add(out);
            }
            return saved;
        });
        sink.onCleared();
        int total = 0;
        for (int i = 0; i < stepTotal; i++) {
            String type = ASSET_EXTRACT_STEPS[i];
            List<WorkspaceDtos.AssetResponse> saved = savedBatches.get(i);
            total += saved.size();
            sink.onAssets(type, saved, i + 1, stepTotal);
            sink.onStatus("已写入 " + ASSET_EXTRACT_STEP_LABEL.get(type) + " " + saved.size()
                    + " 个（" + (i + 1) + "/" + stepTotal + "）");
        }
        return total;
    }

    @Transactional
    public WorkspaceDtos.AssetResponse createAsset(Long projectId, WorkspaceDtos.AssetCreateRequest req) {
        projectService.requireWritableProject(projectId);
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BusinessException("资产名称不能为空");
        }
        Asset asset = new Asset();
        asset.setProjectId(projectId);
        asset.setType(req.getType() != null ? req.getType() : AssetType.character);
        asset.setName(req.getName().trim());
        asset.setDescription(req.getDescription() != null ? req.getDescription() : "");
        asset.setImagePrompt(req.getImagePrompt() != null ? req.getImagePrompt() : "");
        assetRepository.save(asset);
        return toAssetResponse(asset);
    }

    @Transactional
    public WorkspaceDtos.AssetResponse updateAsset(Long projectId, Long assetId,
                                                   WorkspaceDtos.AssetUpdateRequest req) {
        projectService.requireWritableProject(projectId);
        Asset asset = assetRepository.findByIdAndProjectId(assetId, projectId)
                .orElseThrow(() -> new BusinessException("资产不存在"));
        if (req.getType() != null) asset.setType(req.getType());
        if (req.getName() != null) asset.setName(req.getName());
        if (req.getDescription() != null) asset.setDescription(req.getDescription());
        if (req.getImagePrompt() != null) asset.setImagePrompt(req.getImagePrompt());
        assetRepository.save(asset);
        return toAssetResponse(asset);
    }

    @Transactional
    public void deleteAsset(Long projectId, Long assetId) {
        projectService.requireWritableProject(projectId);
        Asset asset = assetRepository.findByIdAndProjectId(assetId, projectId)
                .orElseThrow(() -> new BusinessException("资产不存在"));
        for (Shot shot : shotRepository.findByProjectIdWithRefs(projectId)) {
            List<Long> linked = jsonUtil.parseLongList(shot.getLinkedAssetIdsJson());
            if (linked.remove(assetId)) {
                shot.setLinkedAssetIdsJson(jsonUtil.toJson(linked));
                shotRepository.save(shot);
            }
        }
        assetRepository.delete(asset);
    }

    @Transactional
    public WorkspaceDtos.AssetResponse regenerateAssetPrompt(Long projectId, Long assetId) {
        Project project = projectService.requireWritableProject(projectId);
        Asset asset = assetRepository.findByIdAndProjectId(assetId, projectId)
                .orElseThrow(() -> new BusinessException("资产不存在"));
        try {
            LlmGateway.AssetDto dto = new LlmGateway.AssetDto(
                    asset.getType().name(), asset.getName(), asset.getDescription(), asset.getImagePrompt());
            asset.setImagePrompt(llmGateway.regenerateAssetPrompt(dto, project.getStylePreset()));
            assetRepository.save(asset);
            aiUsageLogService.logSuccess(
                    projectId,
                    AiUsageLogService.TYPE_ASSET_REGENERATE,
                    "重新生成资产提示词：" + asset.getName(),
                    asset.getImagePrompt()
            );
            return toAssetResponse(asset);
        } catch (Exception ex) {
            // LLM 失败时用本地设定图模板兜底，不直接失败
            asset.setImagePrompt(promptCompose.localAssetImagePrompt(asset, project.getStylePreset()));
            assetRepository.save(asset);
            aiUsageLogService.logFailure(
                    projectId,
                    AiUsageLogService.TYPE_ASSET_REGENERATE,
                    "重新生成资产提示词失败，已用本地模板兜底：" + asset.getName(),
                    ex.getMessage()
            );
            return toAssetResponse(asset);
        }
    }

    /** 按指令重写资产介绍与生图提示词 */
    @Transactional
    public WorkspaceDtos.AssetResponse rewriteAsset(Long projectId, Long assetId, String instruction) {
        Project project = projectService.requireWritableProject(projectId);
        Asset asset = assetRepository.findByIdAndProjectId(assetId, projectId)
                .orElseThrow(() -> new BusinessException("资产不存在"));
        LlmGateway.AssetDto rewritten = llmGateway.rewriteAsset(
                new LlmGateway.AssetDto(
                        asset.getType() != null ? asset.getType().name() : "character",
                        asset.getName(),
                        asset.getDescription(),
                        asset.getImagePrompt()
                ),
                project.getStylePreset(),
                instruction
        );
        WorkspaceDtos.AssetUpdateRequest req = new WorkspaceDtos.AssetUpdateRequest();
        if (rewritten.type() != null) {
            req.setType(parseAssetType(rewritten.type()));
        }
        if (rewritten.name() != null && !rewritten.name().isBlank()) {
            req.setName(rewritten.name());
        }
        req.setDescription(rewritten.description());
        req.setImagePrompt(rewritten.imagePrompt());
        return updateAsset(projectId, assetId, req);
    }

    public List<WorkspaceDtos.ShotResponse> listShots(Long projectId) {
        projectService.getProject(projectId);
        return shotRepository.findByProjectIdWithRefs(projectId).stream()
                .map(this::toShotResponse)
                .toList();
    }

    /** 若尚无分镜记录，则按剧本条目创建空分镜占位（不调用 AI）。 */
    @Transactional
    public void ensureShotPlaceholders(Long projectId) {
        projectService.requireWritableProject(projectId);
        if (!shotRepository.findByProjectIdWithRefs(projectId).isEmpty()) {
            return;
        }
        recreateShotPlaceholders(projectId);
    }

    /** 按剧本重建分镜占位行，并挂上关联资产（清空旧分镜）。 */
    private void recreateShotPlaceholders(Long projectId) {
        List<ScriptEntry> scripts = scriptEntryRepository.findByProjectIdOrderByOrderIndexAsc(projectId);
        if (scripts.isEmpty()) {
            throw new BusinessException("请先生成剧本");
        }
        shotRepository.deleteByProjectId(projectId);
        int order = 0;
        List<Asset> assets = assetRepository.findByProjectIdOrderByTypeAscNameAsc(projectId);
        for (ScriptEntry script : scripts) {
            Shot shot = new Shot();
            shot.setProjectId(projectId);
            shot.setScriptId(script.getId());
            shot.setOrderIndex(order++);
            shot.setVideoPrompt("");
            shot.setLinkedAssetIdsJson(jsonUtil.toJson(resolveLinkedAssetIds(script, assets, List.of())));
            shotRepository.save(shot);
        }
    }

    @Transactional
    public List<WorkspaceDtos.ShotResponse> generateShots(Long projectId) {
        return generateShots(projectId, LlmGateway.PromptLanguage.ZH);
    }

    @Transactional
    public List<WorkspaceDtos.ShotResponse> generateShots(Long projectId, LlmGateway.PromptLanguage promptLanguage) {
        Project project = projectService.requireWritableProject(projectId);
        quotaService.checkAiUsage(AiUsageLogService.TYPE_SHOT_GENERATE);
        List<ScriptEntry> scripts = scriptEntryRepository.findByProjectIdOrderByOrderIndexAsc(projectId);
        if (scripts.isEmpty()) {
            throw new BusinessException("请先生成剧本");
        }
        try {
            String scriptJson = jsonUtil.toJson(scripts.stream().map(this::toScriptResponse).toList());
            List<Asset> assets = assetRepository.findByProjectIdOrderByTypeAscNameAsc(projectId);
            String assetsJson = jsonUtil.toJson(listAssets(projectId));
            String refMode = projectSettingsSupport.read(project).normalizedRefPromptMode();
            List<LlmGateway.ShotDto> items = llmGateway.generateShots(
                    scriptJson, assetsJson, project.getStylePreset(),
                    promptLanguage != null ? promptLanguage : LlmGateway.PromptLanguage.ZH,
                    refMode);

            // 保留已有关联：先记下 scriptId -> linked ids，生成提示词后写回
            Map<Long, String> existingLinks = new HashMap<>();
            for (Shot existing : shotRepository.findByProjectIdWithRefs(projectId)) {
                if (existing.getScriptId() != null && existing.getLinkedAssetIdsJson() != null) {
                    existingLinks.put(existing.getScriptId(), existing.getLinkedAssetIdsJson());
                }
            }

            createSnapshot(projectId, "before_shots_generate", "生成分镜提示词前快照");
            shotRepository.deleteByProjectId(projectId);
            int order = 0;
            for (LlmGateway.ShotDto dto : items) {
                Shot shot = new Shot();
                shot.setProjectId(projectId);
                ScriptEntry linkedScript = null;
                if (dto.scriptIndex() >= 0 && dto.scriptIndex() < scripts.size()) {
                    linkedScript = scripts.get(dto.scriptIndex());
                    shot.setScriptId(linkedScript.getId());
                }
                shot.setOrderIndex(order++);
                String scriptDialogue = linkedScript != null ? linkedScript.getDialogue() : null;
                shot.setVideoPrompt(promptCompose.ensureVideoDialogue(dto.videoPrompt(), scriptDialogue));
                if (linkedScript != null && existingLinks.containsKey(linkedScript.getId())) {
                    shot.setLinkedAssetIdsJson(existingLinks.get(linkedScript.getId()));
                } else {
                    shot.setLinkedAssetIdsJson(jsonUtil.toJson(
                            resolveLinkedAssetIds(linkedScript, assets, List.of())));
                }

                int refOrder = 0;
                if (dto.referencePrompts() != null) {
                    for (LlmGateway.ReferencePromptDto ref : dto.referencePrompts()) {
                        ReferencePrompt rp = new ReferencePrompt();
                        rp.setShot(shot);
                        rp.setType(parseRefType(ref.type()));
                        String refPrompt = ref.prompt();
                        if (ProjectSettingsSupport.MODE_SINGLE.equals(refMode)) {
                            refPrompt = promptCompose.ensureStoryboardDialogueOnBoard(
                                    refPrompt, scriptDialogue);
                        }
                        rp.setPrompt(refPrompt);
                        rp.setNote(ref.note());
                        rp.setOrderIndex(refOrder++);
                        shot.getReferencePrompts().add(rp);
                    }
                }
                normalizeRefTypes(shot);
                shotRepository.save(shot);
            }
            aiUsageLogService.logSuccess(
                    projectId,
                    AiUsageLogService.TYPE_SHOT_GENERATE,
                    "生成分镜，共 " + items.size() + " 个",
                    null
            );
            return listShots(projectId);
        } catch (Exception ex) {
            aiUsageLogService.logFailure(
                    projectId,
                    AiUsageLogService.TYPE_SHOT_GENERATE,
                    "生成分镜失败",
                    ex.getMessage()
            );
            throw ex;
        }
    }

    @Transactional
    public WorkspaceDtos.ShotResponse updateShot(Long projectId, Long shotId,
                                                 WorkspaceDtos.ShotUpdateRequest req) {
        projectService.requireWritableProject(projectId);
        Shot shot = shotRepository.findByIdAndProjectIdWithRefs(shotId, projectId)
                .orElseThrow(() -> new BusinessException("分镜不存在"));
        if (req.getVideoPrompt() != null) shot.setVideoPrompt(req.getVideoPrompt());
        if (req.getLinkedAssetIds() != null) shot.setLinkedAssetIdsJson(jsonUtil.toJson(req.getLinkedAssetIds()));
        if (req.getVideoRefMode() != null) {
            String mode = req.getVideoRefMode().trim();
            if (mode.isEmpty()) {
                shot.setVideoRefMode(null);
            } else {
                shot.setVideoRefMode(normalizeVideoRefMode(mode));
            }
        }
        shotRepository.save(shot);
        // 必须 WithRefs：否则 LAZY 集合为空，前端 onShotChange 会冲掉参考图提示词
        return toShotResponse(shotRepository.findByIdAndProjectIdWithRefs(shotId, projectId).orElseThrow());
    }

    /**
     * 设置视频参考方式：
     * - 传入 shotIds：只给这些分镜写覆盖（不改作品默认）
     * - 否则：改作品默认；syncAllShots 时清除全部覆盖
     */
    @Transactional
    public WorkspaceDtos.ApplyVideoRefModeResponse applyVideoRefMode(
            Long projectId, WorkspaceDtos.ApplyVideoRefModeRequest req) {
        projectService.requireWritableProject(projectId);
        if (req == null || req.getMode() == null || req.getMode().isBlank()) {
            throw new BusinessException("请指定参考方式");
        }
        String mode = normalizeVideoRefMode(req.getMode());
        int cleared = 0;
        int updated = 0;

        List<Long> targetIds = req.getShotIds() == null ? List.of() : req.getShotIds().stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();

        if (!targetIds.isEmpty()) {
            Set<Long> idSet = new HashSet<>(targetIds);
            List<Shot> all = shotRepository.findByProjectIdWithRefs(projectId);
            List<Shot> touched = new ArrayList<>();
            for (Shot shot : all) {
                if (!idSet.contains(shot.getId())) {
                    continue;
                }
                shot.setVideoRefMode(mode);
                touched.add(shot);
                updated += 1;
            }
            if (updated != targetIds.size()) {
                throw new BusinessException("部分分镜不存在或无权修改");
            }
            if (!touched.isEmpty()) {
                shotRepository.saveAll(touched);
            }
        } else {
            ProjectDtos.UpdateRequest update = new ProjectDtos.UpdateRequest();
            update.setRefPromptMode(mode);
            projectService.update(projectId, update);

            if (req.isSyncAllShots()) {
                List<Shot> all = shotRepository.findByProjectIdWithRefs(projectId);
                for (Shot shot : all) {
                    if (shot.getVideoRefMode() != null && !shot.getVideoRefMode().isBlank()) {
                        shot.setVideoRefMode(null);
                        cleared += 1;
                    }
                }
                if (cleared > 0) {
                    shotRepository.saveAll(all);
                }
            }
        }

        WorkspaceDtos.ApplyVideoRefModeResponse resp = new WorkspaceDtos.ApplyVideoRefModeResponse();
        resp.setMode(mode);
        resp.setClearedShotOverrides(cleared);
        resp.setUpdatedShotCount(updated);
        resp.setShots(listShots(projectId));
        return resp;
    }

    private static String normalizeVideoRefMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String m = raw.trim().toLowerCase();
        if ("storyboard".equals(m)) {
            return "single";
        }
        if ("first_last".equals(m) || "single".equals(m) || "none".equals(m)) {
            return m;
        }
        throw new BusinessException("不支持的参考方式：" + raw);
    }

    @Transactional
    public List<WorkspaceDtos.ShotResponse> reorderShots(Long projectId, WorkspaceDtos.ShotReorderRequest req) {
        projectService.requireWritableProject(projectId);
        if (req.getShotIds() == null || req.getShotIds().isEmpty()) {
            throw new BusinessException("请提供分镜顺序");
        }
        List<Shot> shots = shotRepository.findByProjectIdWithRefs(projectId);
        if (shots.size() != req.getShotIds().size()) {
            throw new BusinessException("分镜数量不匹配，请刷新后重试");
        }
        Map<Long, Shot> byId = shots.stream().collect(Collectors.toMap(Shot::getId, s -> s));
        Set<Long> seen = new HashSet<>();
        int order = 0;
        for (Long id : req.getShotIds()) {
            Shot shot = byId.get(id);
            if (shot == null) {
                throw new BusinessException("分镜不存在：" + id);
            }
            if (!seen.add(id)) {
                throw new BusinessException("分镜顺序含重复项");
            }
            shot.setOrderIndex(order++);
            shotRepository.save(shot);
        }
        return listShots(projectId);
    }

    @Transactional
    public List<WorkspaceDtos.ShotResponse> insertEmptyShot(Long projectId, WorkspaceDtos.ShotInsertRequest req) {
        projectService.requireWritableProject(projectId);
        List<Shot> shots = new ArrayList<>(shotRepository.findByProjectIdWithRefs(projectId));
        int insertAt = 0;
        if (req.getAfterShotId() != null) {
            int idx = -1;
            for (int i = 0; i < shots.size(); i++) {
                if (shots.get(i).getId().equals(req.getAfterShotId())) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                throw new BusinessException("参照分镜不存在");
            }
            insertAt = idx + 1;
        }

        ScriptEntry script = new ScriptEntry();
        script.setProjectId(projectId);
        script.setTimeLabel("");
        script.setLocation("");
        script.setCharactersJson(jsonUtil.toJson(List.of()));
        script.setAction("");
        script.setDialogue("");
        script.setShot("");
        script.setOrderIndex(nextScriptOrderIndex(projectId));
        scriptEntryRepository.save(script);

        Shot shot = new Shot();
        shot.setProjectId(projectId);
        shot.setScriptId(script.getId());
        shot.setOrderIndex(insertAt);
        shot.setVideoPrompt("");
        shot.setLinkedAssetIdsJson(jsonUtil.toJson(List.of()));
        shotRepository.save(shot);

        shots.add(insertAt, shot);
        renumberShots(shots);
        return listShots(projectId);
    }

    @Transactional
    public List<WorkspaceDtos.ShotResponse> mergeShots(Long projectId, WorkspaceDtos.ShotMergeRequest req) {
        projectService.requireWritableProject(projectId);
        if (req.getPrimaryShotId() == null || req.getSecondaryShotId() == null) {
            throw new BusinessException("请指定要合并的两个分镜");
        }
        if (req.getPrimaryShotId().equals(req.getSecondaryShotId())) {
            throw new BusinessException("不能与自身合并");
        }
        Shot primary = shotRepository.findByIdAndProjectId(req.getPrimaryShotId(), projectId)
                .orElseThrow(() -> new BusinessException("主分镜不存在"));
        Shot secondary = shotRepository.findByIdAndProjectId(req.getSecondaryShotId(), projectId)
                .orElseThrow(() -> new BusinessException("次分镜不存在"));

        ScriptEntry primaryScript = findScript(projectId, primary.getScriptId());
        ScriptEntry secondaryScript = findScript(projectId, secondary.getScriptId());
        if (primaryScript != null && secondaryScript != null) {
            mergeScriptContent(primaryScript, secondaryScript);
            scriptEntryRepository.save(primaryScript);
        } else if (primaryScript == null && secondaryScript != null) {
            primary.setScriptId(secondaryScript.getId());
            secondary.setScriptId(null);
        }

        primary.setVideoPrompt(joinTexts(primary.getVideoPrompt(), secondary.getVideoPrompt()));
        primary.setLinkedAssetIdsJson(jsonUtil.toJson(mergeLongLists(
                jsonUtil.parseLongList(primary.getLinkedAssetIdsJson()),
                jsonUtil.parseLongList(secondary.getLinkedAssetIdsJson()))));

        int refOrder = primary.getReferencePrompts().size();
        for (ReferencePrompt ref : secondary.getReferencePrompts()) {
            ReferencePrompt copy = new ReferencePrompt();
            copy.setShot(primary);
            copy.setType(ref.getType() == ReferenceType.first_frame || ref.getType() == ReferenceType.last_frame
                    ? ReferenceType.other
                    : ref.getType());
            copy.setPrompt(ref.getPrompt());
            copy.setNote(ref.getNote());
            copy.setOrderIndex(refOrder++);
            primary.getReferencePrompts().add(copy);
        }
        normalizeRefTypes(primary);
        shotRepository.save(primary);

        Long secondaryScriptId = secondary.getScriptId();
        shotRepository.delete(secondary);
        if (secondaryScriptId != null && (primaryScript == null || !secondaryScriptId.equals(primaryScript.getId()))) {
            boolean stillUsed = shotRepository.findByProjectIdWithRefs(projectId).stream()
                    .anyMatch(s -> secondaryScriptId.equals(s.getScriptId()));
            if (!stillUsed) {
                scriptEntryRepository.findById(secondaryScriptId).ifPresent(scriptEntryRepository::delete);
            }
        }

        renumberShots(shotRepository.findByProjectIdWithRefs(projectId));
        if (primaryScript != null) {
            relinkShotsForScript(projectId, primaryScript);
        }
        return listShots(projectId);
    }

    @Transactional
    public List<WorkspaceDtos.ShotResponse> splitShot(Long projectId, Long shotId,
                                                      WorkspaceDtos.ShotSplitRequest req) {
        projectService.requireWritableProject(projectId);
        List<Shot> shots = new ArrayList<>(shotRepository.findByProjectIdWithRefs(projectId));
        int index = -1;
        for (int i = 0; i < shots.size(); i++) {
            if (shots.get(i).getId().equals(shotId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new BusinessException("分镜不存在");
        }
        Shot original = shots.get(index);
        ScriptEntry script = findScript(projectId, original.getScriptId());

        String body = script != null ? composeScriptBody(script) : "";
        int splitAt = req.getSplitAt() != null ? req.getSplitAt() : suggestSplitAt(body);
        if (splitAt < 0) splitAt = 0;
        if (splitAt > body.length()) splitAt = body.length();
        String first = body.substring(0, splitAt).trim();
        String second = body.substring(splitAt).trim();
        if (first.isEmpty() && second.isEmpty()) {
            first = "";
            second = "";
        } else if (second.isEmpty() && !first.isEmpty()) {
            // 无法从正文拆出后半段时，后半段留空，允许用户继续编辑
            second = "";
        }

        if (script != null) {
            applyScriptBody(script, first);
            scriptEntryRepository.save(script);
        }

        ScriptEntry newScript = new ScriptEntry();
        newScript.setProjectId(projectId);
        newScript.setTimeLabel(script != null ? nullToEmpty(script.getTimeLabel()) : "");
        newScript.setLocation(script != null ? nullToEmpty(script.getLocation()) : "");
        newScript.setCharactersJson(jsonUtil.toJson(List.of()));
        applyScriptBody(newScript, second);
        newScript.setOrderIndex(nextScriptOrderIndex(projectId));
        scriptEntryRepository.save(newScript);

        String[] promptParts = splitTextInHalf(nullToEmpty(original.getVideoPrompt()));
        original.setVideoPrompt(promptParts[0]);
        shotRepository.save(original);

        Shot created = new Shot();
        created.setProjectId(projectId);
        created.setScriptId(newScript.getId());
        created.setOrderIndex(index + 1);
        created.setVideoPrompt(promptParts[1]);
        created.setLinkedAssetIdsJson(original.getLinkedAssetIdsJson() != null
                ? original.getLinkedAssetIdsJson()
                : jsonUtil.toJson(List.of()));
        shotRepository.save(created);

        shots.add(index + 1, created);
        renumberShots(shots);
        if (script != null) {
            relinkShotsForScript(projectId, script);
        }
        relinkShotsForScript(projectId, newScript);
        return listShots(projectId);
    }

    @Transactional
    public List<WorkspaceDtos.ShotResponse> deleteShot(Long projectId, Long shotId) {
        projectService.requireWritableProject(projectId);
        Shot shot = shotRepository.findByIdAndProjectId(shotId, projectId)
                .orElseThrow(() -> new BusinessException("分镜不存在"));
        Long scriptId = shot.getScriptId();
        shotRepository.delete(shot);
        if (scriptId != null) {
            boolean stillUsed = shotRepository.findByProjectIdWithRefs(projectId).stream()
                    .anyMatch(s -> scriptId.equals(s.getScriptId()));
            if (!stillUsed) {
                scriptEntryRepository.findById(scriptId).ifPresent(scriptEntryRepository::delete);
            }
        }
        renumberShots(shotRepository.findByProjectIdWithRefs(projectId));
        return listShots(projectId);
    }

    @Transactional
    public WorkspaceDtos.ReferencePromptResponse updateReferencePrompt(Long projectId, Long shotId,
                                                                       Long refId, WorkspaceDtos.ReferencePromptUpdateRequest req) {
        projectService.requireWritableProject(projectId);
        Shot shot = shotRepository.findByIdAndProjectId(shotId, projectId)
                .orElseThrow(() -> new BusinessException("分镜不存在"));
        ReferencePrompt ref = shot.getReferencePrompts().stream()
                .filter(r -> r.getId().equals(refId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("参考图提示词不存在"));
        if (req.getType() != null) ref.setType(parseRefType(req.getType()));
        if (req.getPrompt() != null) ref.setPrompt(req.getPrompt());
        if (req.getNote() != null) ref.setNote(req.getNote());
        normalizeRefTypes(shot);
        shotRepository.save(shot);
        return toRefResponse(ref);
    }

    @Transactional
    public List<WorkspaceDtos.ShotResponse> regenerateShotPrompts(Long projectId, Long shotId) {
        return regenerateShotPrompts(projectId, shotId, true, null, null);
    }

    @Transactional
    public List<WorkspaceDtos.ShotResponse> regenerateShotPrompts(Long projectId, Long shotId, boolean snapshot) {
        return regenerateShotPrompts(projectId, shotId, snapshot, null, null);
    }

    @Transactional
    public List<WorkspaceDtos.ShotResponse> regenerateShotPrompts(Long projectId, Long shotId,
                                                                 java.util.function.Consumer<String> onVideoPromptPartial) {
        return regenerateShotPrompts(projectId, shotId, true, onVideoPromptPartial, null);
    }

    @Transactional
    public List<WorkspaceDtos.ShotResponse> regenerateShotPrompts(Long projectId, Long shotId, boolean snapshot,
                                                                 java.util.function.Consumer<String> onVideoPromptPartial) {
        return regenerateShotPrompts(projectId, shotId, snapshot, onVideoPromptPartial, null);
    }

    @Transactional
    public List<WorkspaceDtos.ShotResponse> regenerateShotPrompts(Long projectId, Long shotId, boolean snapshot,
                                                                 java.util.function.Consumer<String> onVideoPromptPartial,
                                                                 LlmGateway.PromptLanguage promptLanguage) {
        return regenerateShotPromptsInternal(projectId, shotId, onVideoPromptPartial, snapshot, promptLanguage);
    }

    public Map<String, Object> exportProject(Long projectId) {
        Project project = projectService.getProject(projectId);
        SourceText source = sourceTextRepository.findByProjectId(projectId).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", Map.of(
                "title", project.getTitle(),
                "style", project.getStylePreset(),
                "exportedAt", java.time.LocalDateTime.now().toString()
        ));
        result.put("source", source != null ? source.getContent() : "");
        result.put("scripts", listScripts(projectId));
        result.put("assets", listAssets(projectId));
        result.put("shots", listShots(projectId));
        return result;
    }

    /**
     * 批量重生准备：更新风格并打快照。
     * 实际 LLM 重生由前端逐条调用 regenerate-prompts，以便展示进度、可中途停止。
     */
    @Transactional
    public List<WorkspaceDtos.ShotResponse> regenerateShotsBatch(Long projectId,
                                                                 WorkspaceDtos.ShotBatchRequest req) {
        projectService.requireWritableProject(projectId);
        if (req != null && req.getStylePreset() != null && !req.getStylePreset().isBlank()) {
            ProjectDtos.UpdateRequest styleReq = new ProjectDtos.UpdateRequest();
            styleReq.setStylePreset(req.getStylePreset().trim());
            projectService.update(projectId, styleReq);
        }
        List<Shot> all = shotRepository.findByProjectIdWithRefs(projectId);
        if (all.isEmpty()) {
            throw new BusinessException("暂无分镜可重新生成");
        }
        int count = req != null && req.getShotIds() != null && !req.getShotIds().isEmpty()
                ? req.getShotIds().size()
                : all.size();
        createSnapshot(projectId, "before_batch_regenerate",
                "批量重新生成前（" + count + " 个分镜）");
        return listShots(projectId);
    }

    public List<WorkspaceDtos.SnapshotResponse> listSnapshots(Long projectId) {
        projectService.getProject(projectId);
        return projectSnapshotRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(this::toSnapshotResponse)
                .toList();
    }

    @Transactional
    public WorkspaceDtos.SnapshotResponse createManualSnapshot(Long projectId, String label) {
        projectService.requireWritableProject(projectId);
        ProjectSnapshot snap = createSnapshot(
                projectId,
                "manual",
                label != null && !label.isBlank() ? label.trim() : "手动快照");
        return toSnapshotResponse(snap);
    }

    @Transactional
    public Map<String, Object> restoreSnapshot(Long projectId, Long snapshotId) {
        projectService.requireWritableProject(projectId);
        ProjectSnapshot snap = projectSnapshotRepository.findByIdAndProjectId(snapshotId, projectId)
                .orElseThrow(() -> new BusinessException("快照不存在"));
        createSnapshot(projectId, "before_restore", "回滚前自动快照");
        restoreFromPayload(projectId, snap.getPayloadJson());
        return exportProject(projectId);
    }

    private List<WorkspaceDtos.ShotResponse> regenerateShotPromptsInternal(
            Long projectId, Long shotId,
            java.util.function.Consumer<String> onVideoPromptPartial,
            boolean takeSnapshot,
            LlmGateway.PromptLanguage promptLanguage) {
        Project project = projectService.requireWritableProject(projectId);
        Shot shot = shotRepository.findByIdAndProjectId(shotId, projectId)
                .orElseThrow(() -> new BusinessException("分镜不存在"));
        if (takeSnapshot) {
            createSnapshot(projectId, "before_shot_regenerate",
                    "重新生成分镜 #" + (shot.getOrderIndex() + 1) + " 前");
        }
        try {
            List<ScriptEntry> scripts = scriptEntryRepository.findByProjectIdOrderByOrderIndexAsc(projectId);
            ScriptEntry linked = scripts.stream().filter(s -> s.getId().equals(shot.getScriptId())).findFirst().orElse(null);
            String scriptJson = linked != null ? jsonUtil.toJson(toScriptResponse(linked)) : "[]";
            List<Asset> assets = assetRepository.findByProjectIdOrderByTypeAscNameAsc(projectId);
            List<Long> linkedIds = jsonUtil.parseLongList(shot.getLinkedAssetIdsJson());
            List<Asset> relevantAssets = assets.stream()
                    .filter(a -> linkedIds.isEmpty() || linkedIds.contains(a.getId()))
                    .limit(8)
                    .toList();
            if (relevantAssets.isEmpty()) {
                relevantAssets = assets.stream().limit(8).toList();
            }
            String assetsJson = jsonUtil.toJson(relevantAssets.stream().map(this::toAssetResponse).toList());
            // 单镜重生始终走轻量流式接口，避免 generateShots 整批划分 + 高 token 上限
            String refMode = resolveShotRefMode(project, shot);
            LlmGateway.ShotDto dto = llmGateway.streamGenerateShot(
                    scriptJson, assetsJson, project.getStylePreset(),
                    promptLanguage != null ? promptLanguage : LlmGateway.PromptLanguage.ZH,
                    refMode,
                    onVideoPromptPartial);
            if (dto != null) {
                String scriptDialogue = linked != null ? linked.getDialogue() : null;
                shot.setVideoPrompt(promptCompose.ensureVideoDialogue(dto.videoPrompt(), scriptDialogue));
                shot.setLinkedAssetIdsJson(jsonUtil.toJson(
                        resolveLinkedAssetIds(linked, assets, List.of())));
                shot.getReferencePrompts().clear();
                int refOrder = 0;
                if (dto.referencePrompts() != null) {
                    for (LlmGateway.ReferencePromptDto ref : dto.referencePrompts()) {
                        ReferencePrompt rp = new ReferencePrompt();
                        rp.setShot(shot);
                        rp.setType(parseRefType(ref.type()));
                        String refPrompt = ref.prompt();
                        if (ProjectSettingsSupport.MODE_SINGLE.equals(refMode)) {
                            refPrompt = promptCompose.ensureStoryboardDialogueOnBoard(
                                    refPrompt, scriptDialogue);
                        }
                        rp.setPrompt(refPrompt);
                        rp.setNote(ref.note());
                        rp.setOrderIndex(refOrder++);
                        shot.getReferencePrompts().add(rp);
                    }
                }
                normalizeRefTypes(shot);
                shotRepository.save(shot);
            }
            aiUsageLogService.logSuccess(
                    projectId,
                    AiUsageLogService.TYPE_SHOT_REGENERATE,
                    "重新生成分镜提示词 #" + (shot.getOrderIndex() + 1),
                    shot.getVideoPrompt()
            );
            return listShots(projectId);
        } catch (RuntimeException ex) {
            // 助手「停止」会在流式回调中抛出取消异常，不算生成失败
            if ("StreamCancelledException".equals(ex.getClass().getSimpleName())) {
                throw ex;
            }
            applyLocalShotPromptFallback(project, shot);
            aiUsageLogService.logFailure(
                    projectId,
                    AiUsageLogService.TYPE_SHOT_REGENERATE,
                    "重新生成分镜提示词失败，已用本地模板兜底 #" + (shot.getOrderIndex() + 1),
                    ex.getMessage()
            );
            return listShots(projectId);
        } catch (Exception ex) {
            applyLocalShotPromptFallback(project, shot);
            aiUsageLogService.logFailure(
                    projectId,
                    AiUsageLogService.TYPE_SHOT_REGENERATE,
                    "重新生成分镜提示词失败，已用本地模板兜底 #" + (shot.getOrderIndex() + 1),
                    ex.getMessage()
            );
            return listShots(projectId);
        }
    }

    /** local first/last/video 模板：LLM 失败时仍写出可用中文提示词 */
    private void applyLocalShotPromptFallback(Project project, Shot shot) {
        List<ScriptEntry> scripts = scriptEntryRepository.findByProjectIdOrderByOrderIndexAsc(project.getId());
        ScriptEntry linked = scripts.stream()
                .filter(s -> s.getId().equals(shot.getScriptId()))
                .findFirst()
                .orElse(null);
        List<Asset> assets = assetRepository.findByProjectIdOrderByTypeAscNameAsc(project.getId());
        List<Long> linkedIds = jsonUtil.parseLongList(shot.getLinkedAssetIdsJson());
        List<Asset> relevant = assets.stream()
                .filter(a -> linkedIds.isEmpty() || linkedIds.contains(a.getId()))
                .limit(8)
                .toList();
        if (relevant.isEmpty()) {
            relevant = assets.stream().limit(8).toList();
        }
        String style = project.getStylePreset();
        String refMode = resolveShotRefMode(project, shot);
        String localVideo = ProjectSettingsSupport.MODE_SINGLE.equals(refMode)
                ? promptCompose.localSingleVideoPrompt(linked, relevant, style)
                : ProjectSettingsSupport.MODE_FIRST_LAST.equals(refMode)
                ? promptCompose.localFlfVideoPrompt(linked, relevant, style)
                : promptCompose.localVideoPrompt(linked, relevant, style);
        String dial = linked != null ? linked.getDialogue() : null;
        shot.setVideoPrompt(promptCompose.ensureVideoDialogue(
                promptCompose.finalizeShotPrompt(localVideo, relevant, style), dial));
        shot.getReferencePrompts().clear();
        if (ProjectSettingsSupport.MODE_NONE.equals(refMode)) {
            // 无参考：不写图片提示词
        } else if (ProjectSettingsSupport.MODE_SINGLE.equals(refMode)) {
            ReferencePrompt main = new ReferencePrompt();
            main.setShot(shot);
            main.setType(ReferenceType.first_frame);
            main.setPrompt(promptCompose.finalizeShotPrompt(
                    promptCompose.localSingleRefPrompt(linked, relevant, style), relevant, style));
            main.setNote("故事版：上参考条+下五格（本地兜底）");
            main.setOrderIndex(0);
            shot.getReferencePrompts().add(main);
        } else {
            ReferencePrompt first = new ReferencePrompt();
            first.setShot(shot);
            first.setType(ReferenceType.first_frame);
            first.setPrompt(promptCompose.finalizeShotPrompt(
                    promptCompose.localFirstFramePrompt(linked, relevant, style), relevant, style));
            first.setNote("首帧：起幅（本地兜底）");
            first.setOrderIndex(0);
            shot.getReferencePrompts().add(first);
            ReferencePrompt last = new ReferencePrompt();
            last.setShot(shot);
            last.setType(ReferenceType.last_frame);
            last.setPrompt(promptCompose.finalizeShotPrompt(
                    promptCompose.localLastFramePrompt(linked, relevant, style), relevant, style));
            last.setNote("尾帧：落幅（本地兜底）");
            last.setOrderIndex(1);
            shot.getReferencePrompts().add(last);
        }
        shotRepository.save(shot);
    }

    /** 分镜覆盖优先，否则作品默认参考模式 */
    private String resolveShotRefMode(Project project, Shot shot) {
        if (shot != null && shot.getVideoRefMode() != null && !shot.getVideoRefMode().isBlank()) {
            return ProjectSettingsSupport.normalizeMode(shot.getVideoRefMode());
        }
        return projectSettingsSupport.read(project).normalizedRefPromptMode();
    }

    private List<String> splitText(String text, int maxLen) {
        List<String> chunks = new ArrayList<>();
        if (text.length() <= maxLen) {
            chunks.add(text);
            return chunks;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxLen, text.length());
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }

    private void normalizeRefTypes(Shot shot) {
        boolean hasFirst = false;
        boolean hasLast = false;
        for (ReferencePrompt ref : shot.getReferencePrompts()) {
            if (ref.getType() == ReferenceType.first_frame) {
                if (hasFirst) ref.setType(ReferenceType.other);
                else hasFirst = true;
            }
            if (ref.getType() == ReferenceType.last_frame) {
                if (hasLast) ref.setType(ReferenceType.other);
                else hasLast = true;
            }
        }
    }

    private AssetType parseAssetType(String type) {
        try {
            return AssetType.valueOf(type.toLowerCase());
        } catch (Exception ex) {
            return AssetType.prop;
        }
    }

    private ReferenceType parseRefType(String type) {
        return ReferenceType.from(type);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Compact script payload for asset extraction — skips shot/id and caps text length. */
    private Map<String, Object> toAssetExtractSummary(ScriptEntry entry) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("location", truncate(entry.getLocation(), 40));
        item.put("characters", jsonUtil.parseStringList(entry.getCharactersJson()));
        item.put("action", truncate(entry.getAction(), 160));
        item.put("dialogue", truncate(entry.getDialogue(), 80));
        return item;
    }

    private List<Map<String, Object>> toAssetLinkSummaries(List<Asset> assets) {
        return assets.stream().map(asset -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", asset.getId());
            item.put("type", asset.getType() != null ? asset.getType().name() : "");
            item.put("name", asset.getName());
            return item;
        }).toList();
    }

    /** Relink all existing shots after assets are extracted/replaced. */
    private void relinkAllShots(Long projectId) {
        List<Asset> assets = assetRepository.findByProjectIdOrderByTypeAscNameAsc(projectId);
        List<ScriptEntry> scripts = scriptEntryRepository.findByProjectIdOrderByOrderIndexAsc(projectId);
        Map<Long, ScriptEntry> scriptById = scripts.stream()
                .collect(Collectors.toMap(ScriptEntry::getId, s -> s, (a, b) -> a));
        for (Shot shot : shotRepository.findByProjectIdWithRefs(projectId)) {
            ScriptEntry script = shot.getScriptId() != null ? scriptById.get(shot.getScriptId()) : null;
            shot.setLinkedAssetIdsJson(jsonUtil.toJson(resolveLinkedAssetIds(script, assets, List.of())));
            shotRepository.save(shot);
        }
    }

    private void relinkShotsForScript(Long projectId, ScriptEntry script) {
        if (script == null || script.getId() == null) {
            return;
        }
        List<Asset> assets = assetRepository.findByProjectIdOrderByTypeAscNameAsc(projectId);
        String linkedJson = jsonUtil.toJson(resolveLinkedAssetIds(script, assets, List.of()));
        for (Shot shot : shotRepository.findByProjectIdWithRefs(projectId)) {
            if (script.getId().equals(shot.getScriptId())) {
                shot.setLinkedAssetIdsJson(linkedJson);
                shotRepository.save(shot);
            }
        }
    }

    /**
     * Resolve shot-linked assets from LLM hints plus script content matching.
     * Hints may be asset ids or names.
     */
    private List<Long> resolveLinkedAssetIds(ScriptEntry script, List<Asset> assets, List<String> hints) {
        if (assets == null || assets.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();

        if (hints != null) {
            for (String hint : hints) {
                if (hint == null || hint.isBlank()) {
                    continue;
                }
                String value = hint.trim();
                try {
                    Long id = Long.parseLong(value);
                    assets.stream().filter(a -> id.equals(a.getId())).findFirst()
                            .ifPresent(a -> ids.add(a.getId()));
                    continue;
                } catch (NumberFormatException ignored) {
                }
                for (Asset asset : assets) {
                    if (nameMatches(asset.getName(), value)) {
                        ids.add(asset.getId());
                    }
                }
            }
        }

        if (script == null) {
            return new ArrayList<>(ids);
        }

        List<String> characters = jsonUtil.parseStringList(script.getCharactersJson());
        String location = script.getLocation() != null ? script.getLocation() : "";
        String haystack = String.join("\n",
                nullToEmpty(script.getAction()),
                nullToEmpty(script.getDialogue()),
                nullToEmpty(script.getShot()),
                location).toLowerCase();

        for (Asset asset : assets) {
            String name = asset.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            AssetType type = asset.getType();
            if (type == AssetType.character) {
                boolean matched = characters.stream().anyMatch(c -> characterMatches(name, c));
                if (!matched) {
                    for (String token : nameTokens(name)) {
                        if (token.length() >= 2 && haystack.contains(token.toLowerCase())) {
                            matched = true;
                            break;
                        }
                    }
                }
                if (matched) {
                    ids.add(asset.getId());
                }
            } else if (type == AssetType.scene) {
                if (sceneMatches(name, location) || significantTokenMatch(haystack, name)) {
                    ids.add(asset.getId());
                }
            } else if (type == AssetType.prop) {
                if (haystack.contains(name.toLowerCase()) || significantTokenMatch(haystack, name)) {
                    ids.add(asset.getId());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private boolean sceneMatches(String assetName, String location) {
        if (nameMatches(assetName, location)) {
            return true;
        }
        if (location == null || location.isBlank() || assetName == null) {
            return false;
        }
        Set<String> locationTokens = nameTokens(location);
        for (String part : locationTokens) {
            if (part.length() >= 2 && assetName.contains(part)) {
                return true;
            }
        }
        for (String part : nameTokens(assetName)) {
            if (part.length() >= 2 && location.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private boolean significantTokenMatch(String haystackLower, String name) {
        for (String part : nameTokens(name)) {
            if (part.length() >= 2 && haystackLower.contains(part.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** Character asset ↔ script character: exact, prefix with delimiter, or boundary segment. */
    private boolean characterMatches(String assetName, String character) {
        if (assetName == null || character == null) {
            return false;
        }
        String a = assetName.trim().toLowerCase();
        String c = character.trim().toLowerCase();
        if (a.isEmpty() || c.isEmpty() || c.length() < 2) {
            return false;
        }
        if (a.equals(c) || c.contains(a)) {
            return true;
        }
        if (a.startsWith(c) && (a.length() == c.length() || isNameBoundary(a.charAt(c.length())))) {
            return true;
        }
        int from = 0;
        while ((from = a.indexOf(c, from)) >= 0) {
            boolean beforeOk = from == 0 || isNameBoundary(a.charAt(from - 1));
            int end = from + c.length();
            boolean afterOk = end >= a.length() || isNameBoundary(a.charAt(end));
            if (beforeOk && (afterOk || end < a.length())) {
                return true;
            }
            from++;
        }
        return false;
    }

    private Set<String> nameTokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return tokens;
        }
        for (String part : value.split("[·、,/／\\s（）()\\[\\]【】]+")) {
            String token = part.trim();
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private boolean isNameBoundary(char ch) {
        return "（）()/／·_－-、,， ".indexOf(ch) >= 0 || Character.isWhitespace(ch);
    }

    private boolean nameMatches(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = a.trim().toLowerCase();
        String y = b.trim().toLowerCase();
        if (x.isEmpty() || y.isEmpty()) {
            return false;
        }
        return x.equals(y) || x.contains(y) || y.contains(x);
    }

    private ProjectSnapshot createSnapshot(Long projectId, String kind, String label) {
        ProjectSnapshot snap = new ProjectSnapshot();
        snap.setProjectId(projectId);
        snap.setKind(kind);
        snap.setLabel(label);
        snap.setPayloadJson(jsonUtil.toJson(exportProject(projectId)));
        projectSnapshotRepository.save(snap);
        List<ProjectSnapshot> all = projectSnapshotRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        if (all.size() > SNAPSHOT_KEEP_LIMIT) {
            for (int i = SNAPSHOT_KEEP_LIMIT; i < all.size(); i++) {
                projectSnapshotRepository.delete(all.get(i));
            }
        }
        return snap;
    }

    private void restoreFromPayload(Long projectId, String payloadJson) {
        Map<String, Object> payload = jsonUtil.parseMap(payloadJson);
        if (payload.isEmpty()) {
            throw new BusinessException("快照内容无效");
        }

        SourceText source = sourceTextRepository.findByProjectId(projectId)
                .orElseThrow(() -> new BusinessException("原文不存在"));
        Object sourceObj = payload.get("source");
        source.setContent(sourceObj != null ? String.valueOf(sourceObj) : "");
        sourceTextRepository.save(source);

        Object projectObj = payload.get("project");
        if (projectObj instanceof Map<?, ?> projectMap && projectMap.get("style") != null) {
            ProjectDtos.UpdateRequest styleReq = new ProjectDtos.UpdateRequest();
            styleReq.setStylePreset(String.valueOf(projectMap.get("style")));
            projectService.update(projectId, styleReq);
        }

        shotRepository.deleteByProjectId(projectId);
        scriptEntryRepository.deleteByProjectId(projectId);
        assetRepository.deleteByProjectId(projectId);

        List<Map<String, Object>> assetMaps = castMapList(payload.get("assets"));
        Map<Long, Long> assetIdMap = new HashMap<>();
        for (Map<String, Object> item : assetMaps) {
            Asset asset = new Asset();
            asset.setProjectId(projectId);
            asset.setType(parseAssetType(String.valueOf(item.getOrDefault("type", "character"))));
            asset.setName(String.valueOf(item.getOrDefault("name", "未命名")));
            asset.setDescription(String.valueOf(item.getOrDefault("description", "")));
            asset.setImagePrompt(String.valueOf(item.getOrDefault("imagePrompt", "")));
            Object refFile = item.get("referenceImageFile");
            if (refFile != null && !String.valueOf(refFile).isBlank()) {
                asset.setReferenceImageFile(String.valueOf(refFile));
            }
            assetRepository.save(asset);
            Long oldId = asLong(item.get("id"));
            if (oldId != null) {
                assetIdMap.put(oldId, asset.getId());
            }
        }

        List<Map<String, Object>> scriptMaps = castMapList(payload.get("scripts"));
        scriptMaps.sort(Comparator.comparingInt(m -> asInt(m.get("orderIndex"), 0)));
        Map<Long, Long> scriptIdMap = new HashMap<>();
        int scriptOrder = 0;
        for (Map<String, Object> item : scriptMaps) {
            ScriptEntry entry = new ScriptEntry();
            entry.setProjectId(projectId);
            entry.setTimeLabel(String.valueOf(item.getOrDefault("time", "")));
            entry.setLocation(String.valueOf(item.getOrDefault("location", "")));
            Object characters = item.get("characters");
            entry.setCharactersJson(jsonUtil.toJson(characters instanceof List<?> list ? list : List.of()));
            entry.setAction(String.valueOf(item.getOrDefault("action", "")));
            entry.setDialogue(String.valueOf(item.getOrDefault("dialogue", "")));
            entry.setShot(String.valueOf(item.getOrDefault("shot", "")));
            entry.setOrderIndex(scriptOrder++);
            scriptEntryRepository.save(entry);
            Long oldId = asLong(item.get("id"));
            if (oldId != null) {
                scriptIdMap.put(oldId, entry.getId());
            }
        }

        List<Map<String, Object>> shotMaps = castMapList(payload.get("shots"));
        shotMaps.sort(Comparator.comparingInt(m -> asInt(m.get("orderIndex"), 0)));
        int shotOrder = 0;
        for (Map<String, Object> item : shotMaps) {
            Shot shot = new Shot();
            shot.setProjectId(projectId);
            Long oldScriptId = asLong(item.get("scriptId"));
            if (oldScriptId != null) {
                shot.setScriptId(scriptIdMap.get(oldScriptId));
            }
            shot.setOrderIndex(shotOrder++);
            shot.setVideoPrompt(String.valueOf(item.getOrDefault("videoPrompt", "")));
            List<Long> linked = new ArrayList<>();
            Object linkedObj = item.get("linkedAssetIds");
            if (linkedObj instanceof List<?> list) {
                for (Object idObj : list) {
                    Long oldAssetId = asLong(idObj);
                    if (oldAssetId != null && assetIdMap.containsKey(oldAssetId)) {
                        linked.add(assetIdMap.get(oldAssetId));
                    }
                }
            }
            shot.setLinkedAssetIdsJson(jsonUtil.toJson(linked));

            Object refsObj = item.get("referencePrompts");
            if (refsObj instanceof List<?> refs) {
                int refOrder = 0;
                for (Object refObj : refs) {
                    if (!(refObj instanceof Map<?, ?> rawRef)) continue;
                    Map<String, Object> refMap = new LinkedHashMap<>();
                    rawRef.forEach((k, v) -> refMap.put(String.valueOf(k), v));
                    ReferencePrompt rp = new ReferencePrompt();
                    rp.setShot(shot);
                    rp.setType(parseRefType(String.valueOf(refMap.getOrDefault("type", "other"))));
                    rp.setPrompt(String.valueOf(refMap.getOrDefault("prompt", "")));
                    rp.setNote(String.valueOf(refMap.getOrDefault("note", "")));
                    Object refImg = refMap.get("referenceImageFile");
                    if (refImg != null && !String.valueOf(refImg).isBlank()) {
                        rp.setReferenceImageFile(String.valueOf(refImg));
                    }
                    rp.setOrderIndex(refOrder++);
                    shot.getReferencePrompts().add(rp);
                }
            }
            normalizeRefTypes(shot);
            shotRepository.save(shot);
        }
    }

    private List<Map<String, Object>> castMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                result.add(copy);
            }
        }
        return result;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private WorkspaceDtos.SnapshotResponse toSnapshotResponse(ProjectSnapshot snap) {
        WorkspaceDtos.SnapshotResponse resp = new WorkspaceDtos.SnapshotResponse();
        resp.setId(snap.getId());
        resp.setKind(snap.getKind());
        resp.setLabel(snap.getLabel());
        resp.setCreatedAt(snap.getCreatedAt() != null ? snap.getCreatedAt().toString() : "");
        return resp;
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private void renumberShots(List<Shot> shots) {
        int order = 0;
        for (Shot shot : shots) {
            shot.setOrderIndex(order++);
            shotRepository.save(shot);
        }
    }

    private int nextScriptOrderIndex(Long projectId) {
        return scriptEntryRepository.findByProjectIdOrderByOrderIndexAsc(projectId).stream()
                .mapToInt(ScriptEntry::getOrderIndex)
                .max()
                .orElse(-1) + 1;
    }

    private ScriptEntry findScript(Long projectId, Long scriptId) {
        if (scriptId == null) return null;
        return scriptEntryRepository.findById(scriptId)
                .filter(s -> s.getProjectId().equals(projectId))
                .orElse(null);
    }

    private String composeScriptBody(ScriptEntry script) {
        List<String> parts = new ArrayList<>();
        List<String> characters = jsonUtil.parseStringList(script.getCharactersJson());
        if (characters != null && !characters.isEmpty()) {
            parts.add(String.join("、", characters));
        }
        if (script.getAction() != null && !script.getAction().isBlank()) {
            parts.add(script.getAction().trim());
        }
        if (script.getDialogue() != null && !script.getDialogue().isBlank()) {
            parts.add(script.getDialogue().trim());
        }
        if (script.getShot() != null && !script.getShot().isBlank()) {
            parts.add(script.getShot().trim());
        }
        return String.join("\n\n", parts);
    }

    private void applyScriptBody(ScriptEntry script, String body) {
        script.setCharactersJson(jsonUtil.toJson(List.of()));
        script.setAction(body != null ? body : "");
        script.setDialogue("");
        script.setShot("");
    }

    private void mergeScriptContent(ScriptEntry primary, ScriptEntry secondary) {
        String merged = joinTexts(composeScriptBody(primary), composeScriptBody(secondary));
        applyScriptBody(primary, merged);
        if ((primary.getTimeLabel() == null || primary.getTimeLabel().isBlank())
                && secondary.getTimeLabel() != null && !secondary.getTimeLabel().isBlank()) {
            primary.setTimeLabel(secondary.getTimeLabel());
        }
        if ((primary.getLocation() == null || primary.getLocation().isBlank())
                && secondary.getLocation() != null && !secondary.getLocation().isBlank()) {
            primary.setLocation(secondary.getLocation());
        }
    }

    private String joinTexts(String a, String b) {
        String left = a != null ? a.trim() : "";
        String right = b != null ? b.trim() : "";
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        return left + "\n\n" + right;
    }

    private List<Long> mergeLongLists(List<Long> a, List<Long> b) {
        LinkedHashSet<Long> set = new LinkedHashSet<>();
        if (a != null) set.addAll(a);
        if (b != null) set.addAll(b);
        return new ArrayList<>(set);
    }

    private int suggestSplitAt(String body) {
        if (body == null || body.isBlank()) return 0;
        int blank = body.indexOf("\n\n");
        if (blank > 0 && blank < body.length() - 2) {
            return blank + 2;
        }
        int mid = body.length() / 2;
        int nearest = body.indexOf('\n', mid);
        if (nearest > 0) return nearest + 1;
        return mid;
    }

    private String[] splitTextInHalf(String text) {
        if (text == null || text.isBlank()) {
            return new String[]{"", ""};
        }
        int at = suggestSplitAt(text);
        return new String[]{text.substring(0, at).trim(), text.substring(at).trim()};
    }

    private WorkspaceDtos.ScriptResponse toScriptResponse(ScriptEntry entry) {
        WorkspaceDtos.ScriptResponse resp = new WorkspaceDtos.ScriptResponse();
        resp.setId(entry.getId());
        resp.setTime(entry.getTimeLabel());
        resp.setLocation(entry.getLocation());
        resp.setCharacters(jsonUtil.parseStringList(entry.getCharactersJson()));
        resp.setAction(entry.getAction());
        resp.setDialogue(entry.getDialogue());
        resp.setShot(entry.getShot());
        resp.setOrderIndex(entry.getOrderIndex());
        return resp;
    }

    private WorkspaceDtos.AssetResponse toAssetResponse(Asset asset) {
        WorkspaceDtos.AssetResponse resp = new WorkspaceDtos.AssetResponse();
        resp.setId(asset.getId());
        resp.setType(asset.getType());
        resp.setName(asset.getName());
        resp.setDescription(asset.getDescription());
        resp.setImagePrompt(asset.getImagePrompt());
        resp.setReferenceImageFile(asset.getReferenceImageFile());
        if (asset.getReferenceImageFile() != null && !asset.getReferenceImageFile().isBlank()) {
            String publicUrl = mediaVaultService.publicAssetUrl(asset.getReferenceImageFile());
            resp.setReferenceImageUrl(publicUrl != null
                    ? publicUrl
                    : String.format("/projects/%d/assets/%d/image", asset.getProjectId(), asset.getId()));
        }
        return resp;
    }

    private WorkspaceDtos.ShotResponse toShotResponse(Shot shot) {
        WorkspaceDtos.ShotResponse resp = new WorkspaceDtos.ShotResponse();
        resp.setId(shot.getId());
        resp.setScriptId(shot.getScriptId());
        resp.setOrderIndex(shot.getOrderIndex());
        resp.setVideoPrompt(shot.getVideoPrompt());
        resp.setVideoRefMode(shot.getVideoRefMode());
        resp.setLinkedAssetIds(jsonUtil.parseLongList(shot.getLinkedAssetIdsJson()));
        resp.setReferencePrompts(shot.getReferencePrompts().stream().map(this::toRefResponse).toList());
        return resp;
    }

    private WorkspaceDtos.ReferencePromptResponse toRefResponse(ReferencePrompt ref) {
        WorkspaceDtos.ReferencePromptResponse resp = new WorkspaceDtos.ReferencePromptResponse();
        resp.setId(ref.getId());
        resp.setType(ref.getType());
        resp.setPrompt(ref.getPrompt());
        resp.setNote(ref.getNote());
        resp.setOrderIndex(ref.getOrderIndex());
        resp.setReferenceImageFile(ref.getReferenceImageFile());
        if (ref.getReferenceImageFile() != null && !ref.getReferenceImageFile().isBlank()) {
            String publicUrl = mediaVaultService.publicAssetUrl(ref.getReferenceImageFile());
            Shot shot = ref.getShot();
            resp.setReferenceImageUrl(publicUrl != null
                    ? publicUrl
                    : String.format("/projects/%d/shots/%d/refs/%d/image",
                    shot.getProjectId(), shot.getId(), ref.getId()));
        }
        return resp;
    }
}
