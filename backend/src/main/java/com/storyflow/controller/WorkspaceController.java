package com.storyflow.controller;

import com.storyflow.ai.AiRequestOverride;
import com.storyflow.common.ApiResponse;
import com.storyflow.dto.WorkspaceDtos;
import com.storyflow.entity.User;
import com.storyflow.service.AssetImageService;
import com.storyflow.service.CurrentUserService;
import com.storyflow.service.ShotReferenceImageService;
import com.storyflow.service.VideoProductionService;
import com.storyflow.service.WorkspaceService;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final VideoProductionService videoProductionService;
    private final AssetImageService assetImageService;
    private final ShotReferenceImageService shotReferenceImageService;
    private final CurrentUserService currentUserService;
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    public WorkspaceController(
            WorkspaceService workspaceService,
            VideoProductionService videoProductionService,
            AssetImageService assetImageService,
            ShotReferenceImageService shotReferenceImageService,
            CurrentUserService currentUserService) {
        this.workspaceService = workspaceService;
        this.videoProductionService = videoProductionService;
        this.assetImageService = assetImageService;
        this.shotReferenceImageService = shotReferenceImageService;
        this.currentUserService = currentUserService;
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

    @GetMapping("/source")
    public ApiResponse<WorkspaceDtos.SourceTextResponse> getSource(@PathVariable Long projectId) {
        return ApiResponse.ok(workspaceService.getSource(projectId));
    }

    @PutMapping("/source")
    public ApiResponse<WorkspaceDtos.SourceTextResponse> updateSource(@PathVariable Long projectId,
                                                                      @RequestBody WorkspaceDtos.SourceTextRequest req) {
        return ApiResponse.ok(workspaceService.updateSource(projectId, req));
    }

    @PostMapping("/script/generate")
    public ApiResponse<List<WorkspaceDtos.ScriptResponse>> generateScript(@PathVariable Long projectId) {
        return ApiResponse.ok(workspaceService.generateScript(projectId));
    }

    /** SSE：按原文分块生成剧本，每块落库后推送 scripts，结束时推送 done。 */
    @PostMapping(value = "/script/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateScriptStream(@PathVariable Long projectId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");

        SseEmitter emitter = new SseEmitter(600_000L);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(ex -> cancelled.set(true));
        emitter.onCompletion(() -> cancelled.set(true));

        AiRequestOverride.Override aiOverride = AiRequestOverride.get();
        User streamUser = currentUserService.getRequestUser();

        streamExecutor.execute(() -> {
            AiRequestOverride.set(aiOverride);
            if (streamUser != null) {
                currentUserService.setRequestUser(streamUser);
            }
            try {
                workspaceService.generateScriptStreaming(projectId, new WorkspaceService.ScriptStreamListener() {
                    @Override
                    public void onStatus(String text) {
                        sendScriptStreamEvent(emitter, cancelled, "status", Map.of("text", text));
                    }

                    @Override
                    public void onCleared() {
                        sendScriptStreamEvent(emitter, cancelled, "cleared", Map.of());
                    }

                    @Override
                    public void onScripts(
                            List<WorkspaceDtos.ScriptResponse> batch, int chunkIndex, int chunkTotal) {
                        sendScriptStreamEvent(emitter, cancelled, "scripts", Map.of(
                                "items", batch,
                                "chunkIndex", chunkIndex,
                                "chunkTotal", chunkTotal
                        ));
                    }

                    @Override
                    public void onAssetsCleared() {
                        sendScriptStreamEvent(emitter, cancelled, "assets_cleared", Map.of());
                    }

                    @Override
                    public void onAssets(
                            String type,
                            List<WorkspaceDtos.AssetResponse> batch,
                            int stepIndex,
                            int stepTotal) {
                        sendScriptStreamEvent(emitter, cancelled, "assets", Map.of(
                                "type", type,
                                "items", batch,
                                "stepIndex", stepIndex,
                                "stepTotal", stepTotal
                        ));
                    }

                    @Override
                    public void onDone(
                            List<WorkspaceDtos.ScriptResponse> scripts,
                            List<WorkspaceDtos.ShotResponse> shots,
                            List<WorkspaceDtos.AssetResponse> assets) {
                        sendScriptStreamEvent(emitter, cancelled, "done", Map.of(
                                "scripts", scripts,
                                "shots", shots,
                                "assets", assets
                        ));
                    }
                });
                if (!cancelled.get()) {
                    try {
                        emitter.complete();
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
            } catch (Exception ex) {
                try {
                    sendScriptStreamEvent(emitter, cancelled, "error", Map.of(
                            "message", ex.getMessage() != null ? ex.getMessage() : "生成剧本失败"
                    ));
                    emitter.complete();
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

    private void sendScriptStreamEvent(
            SseEmitter emitter, AtomicBoolean cancelled, String name, Object data) {
        if (cancelled.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            cancelled.set(true);
        }
    }

    @GetMapping("/script")
    public ApiResponse<List<WorkspaceDtos.ScriptResponse>> listScripts(@PathVariable Long projectId) {
        return ApiResponse.ok(workspaceService.listScripts(projectId));
    }

    @PutMapping("/script/{scriptId:\\d+}")
    public ApiResponse<WorkspaceDtos.ScriptResponse> updateScript(@PathVariable Long projectId,
                                                                    @PathVariable Long scriptId,
                                                                    @RequestBody WorkspaceDtos.ScriptUpdateRequest req) {
        return ApiResponse.ok(workspaceService.updateScript(projectId, scriptId, req));
    }

    @PostMapping("/assets/extract")
    public ApiResponse<List<WorkspaceDtos.AssetResponse>> extractAssets(@PathVariable Long projectId) {
        return ApiResponse.ok(workspaceService.extractAssets(projectId));
    }

    /** SSE：按人物→场景→道具逐步提取落库，实时推送 status / cleared / assets / done。 */
    @PostMapping(value = "/assets/extract/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter extractAssetsStream(@PathVariable Long projectId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");

        SseEmitter emitter = new SseEmitter(600_000L);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(ex -> cancelled.set(true));
        emitter.onCompletion(() -> cancelled.set(true));

        AiRequestOverride.Override aiOverride = AiRequestOverride.get();
        User streamUser = currentUserService.getRequestUser();

        streamExecutor.execute(() -> {
            AiRequestOverride.set(aiOverride);
            if (streamUser != null) {
                currentUserService.setRequestUser(streamUser);
            }
            try {
                workspaceService.extractAssetsStreaming(projectId, new WorkspaceService.AssetExtractListener() {
                    @Override
                    public void onStatus(String text) {
                        sendScriptStreamEvent(emitter, cancelled, "status", Map.of("text", text));
                    }

                    @Override
                    public void onCleared() {
                        sendScriptStreamEvent(emitter, cancelled, "cleared", Map.of());
                    }

                    @Override
                    public void onAssets(
                            String type,
                            List<WorkspaceDtos.AssetResponse> batch,
                            int stepIndex,
                            int stepTotal) {
                        sendScriptStreamEvent(emitter, cancelled, "assets", Map.of(
                                "type", type,
                                "items", batch,
                                "stepIndex", stepIndex,
                                "stepTotal", stepTotal
                        ));
                    }

                    @Override
                    public void onDone(List<WorkspaceDtos.AssetResponse> assets) {
                        sendScriptStreamEvent(emitter, cancelled, "done", Map.of("assets", assets));
                    }
                });
                if (!cancelled.get()) {
                    try {
                        emitter.complete();
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
            } catch (Exception ex) {
                try {
                    sendScriptStreamEvent(emitter, cancelled, "error", Map.of(
                            "message", ex.getMessage() != null ? ex.getMessage() : "提取资产失败"
                    ));
                    emitter.complete();
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

    @GetMapping("/assets")
    public ApiResponse<List<WorkspaceDtos.AssetResponse>> listAssets(@PathVariable Long projectId) {
        return ApiResponse.ok(workspaceService.listAssets(projectId));
    }

    @PostMapping("/assets")
    public ApiResponse<WorkspaceDtos.AssetResponse> createAsset(
            @PathVariable Long projectId,
            @RequestBody WorkspaceDtos.AssetCreateRequest req) {
        return ApiResponse.ok(workspaceService.createAsset(projectId, req));
    }

    @PutMapping("/assets/{assetId:\\d+}")
    public ApiResponse<WorkspaceDtos.AssetResponse> updateAsset(@PathVariable Long projectId,
                                                                @PathVariable Long assetId,
                                                                @RequestBody WorkspaceDtos.AssetUpdateRequest req) {
        return ApiResponse.ok(workspaceService.updateAsset(projectId, assetId, req));
    }

    @DeleteMapping("/assets/{assetId:\\d+}")
    public ApiResponse<Void> deleteAsset(@PathVariable Long projectId, @PathVariable Long assetId) {
        workspaceService.deleteAsset(projectId, assetId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/assets/{assetId:\\d+}/regenerate-prompt")
    public ApiResponse<WorkspaceDtos.AssetResponse> regenerateAssetPrompt(@PathVariable Long projectId,
                                                                          @PathVariable Long assetId) {
        return ApiResponse.ok(workspaceService.regenerateAssetPrompt(projectId, assetId));
    }

    @PostMapping("/assets/{assetId:\\d+}/generate-image")
    public ApiResponse<WorkspaceDtos.AssetResponse> generateAssetImage(
            @PathVariable Long projectId,
            @PathVariable Long assetId,
            @RequestBody(required = false) WorkspaceDtos.AssetImageGenerateRequest req) {
        return ApiResponse.ok(assetImageService.generateImage(projectId, assetId, req));
    }

    @PostMapping("/assets/generate-images")
    public ApiResponse<List<WorkspaceDtos.AssetResponse>> generateAssetImagesBatch(
            @PathVariable Long projectId,
            @RequestBody(required = false) WorkspaceDtos.AssetImageBatchRequest req) {
        return ApiResponse.ok(assetImageService.generateImagesBatch(projectId, req));
    }

    @GetMapping("/assets/{assetId:\\d+}/image")
    public ResponseEntity<?> streamAssetImage(
            @PathVariable Long projectId,
            @PathVariable Long assetId) {
        return assetImageService.streamImage(projectId, assetId);
    }

    @PostMapping("/shots/generate")
    public ApiResponse<List<WorkspaceDtos.ShotResponse>> generateShots(@PathVariable Long projectId) {
        return ApiResponse.ok(workspaceService.generateShots(projectId));
    }

    @GetMapping("/shots")
    public ApiResponse<List<WorkspaceDtos.ShotResponse>> listShots(@PathVariable Long projectId) {
        return ApiResponse.ok(workspaceService.listShots(projectId));
    }

    @PutMapping("/shots/reorder")
    public ApiResponse<List<WorkspaceDtos.ShotResponse>> reorderShots(
            @PathVariable Long projectId,
            @RequestBody WorkspaceDtos.ShotReorderRequest req) {
        return ApiResponse.ok(workspaceService.reorderShots(projectId, req));
    }

    @PostMapping("/shots/insert")
    public ApiResponse<List<WorkspaceDtos.ShotResponse>> insertShot(
            @PathVariable Long projectId,
            @RequestBody(required = false) WorkspaceDtos.ShotInsertRequest req) {
        return ApiResponse.ok(workspaceService.insertEmptyShot(projectId, req != null ? req : new WorkspaceDtos.ShotInsertRequest()));
    }

    @PostMapping("/shots/merge")
    public ApiResponse<List<WorkspaceDtos.ShotResponse>> mergeShots(
            @PathVariable Long projectId,
            @RequestBody WorkspaceDtos.ShotMergeRequest req) {
        return ApiResponse.ok(workspaceService.mergeShots(projectId, req));
    }

    @PostMapping("/shots/regenerate-batch")
    public ApiResponse<List<WorkspaceDtos.ShotResponse>> regenerateShotsBatch(
            @PathVariable Long projectId,
            @RequestBody(required = false) WorkspaceDtos.ShotBatchRequest req) {
        return ApiResponse.ok(workspaceService.regenerateShotsBatch(
                projectId, req != null ? req : new WorkspaceDtos.ShotBatchRequest()));
    }

    /**
     * 参考方式：传 shotIds 只改已选分镜；否则改作品默认（可 syncAllShots 统一全部）。
     */
    @PostMapping("/shots/apply-video-ref-mode")
    public ApiResponse<WorkspaceDtos.ApplyVideoRefModeResponse> applyVideoRefMode(
            @PathVariable Long projectId,
            @RequestBody WorkspaceDtos.ApplyVideoRefModeRequest req) {
        return ApiResponse.ok(workspaceService.applyVideoRefMode(projectId, req));
    }

    @PostMapping("/shots/{shotId:\\d+}/split")
    public ApiResponse<List<WorkspaceDtos.ShotResponse>> splitShot(
            @PathVariable Long projectId,
            @PathVariable Long shotId,
            @RequestBody(required = false) WorkspaceDtos.ShotSplitRequest req) {
        return ApiResponse.ok(workspaceService.splitShot(
                projectId, shotId, req != null ? req : new WorkspaceDtos.ShotSplitRequest()));
    }

    @DeleteMapping("/shots/{shotId:\\d+}")
    public ApiResponse<List<WorkspaceDtos.ShotResponse>> deleteShot(
            @PathVariable Long projectId,
            @PathVariable Long shotId) {
        return ApiResponse.ok(workspaceService.deleteShot(projectId, shotId));
    }

    @PutMapping("/shots/{shotId:\\d+}")
    public ApiResponse<WorkspaceDtos.ShotResponse> updateShot(@PathVariable Long projectId,
                                                              @PathVariable Long shotId,
                                                              @RequestBody WorkspaceDtos.ShotUpdateRequest req) {
        return ApiResponse.ok(workspaceService.updateShot(projectId, shotId, req));
    }

    @PostMapping("/shots/{shotId:\\d+}/regenerate-prompts")
    public ApiResponse<List<WorkspaceDtos.ShotResponse>> regenerateShotPrompts(
            @PathVariable Long projectId,
            @PathVariable Long shotId,
            @RequestParam(defaultValue = "true") boolean snapshot) {
        return ApiResponse.ok(workspaceService.regenerateShotPrompts(projectId, shotId, snapshot));
    }

    @PutMapping("/shots/{shotId:\\d+}/refs/{refId:\\d+}")
    public ApiResponse<WorkspaceDtos.ReferencePromptResponse> updateReferencePrompt(
            @PathVariable Long projectId,
            @PathVariable Long shotId,
            @PathVariable Long refId,
            @RequestBody WorkspaceDtos.ReferencePromptUpdateRequest req) {
        return ApiResponse.ok(workspaceService.updateReferencePrompt(projectId, shotId, refId, req));
    }

    @PostMapping("/shots/{shotId:\\d+}/refs/{refId:\\d+}/generate-image")
    public ApiResponse<WorkspaceDtos.ReferencePromptResponse> generateShotReferenceImage(
            @PathVariable Long projectId,
            @PathVariable Long shotId,
            @PathVariable Long refId,
            @RequestBody(required = false) WorkspaceDtos.ReferenceImageGenerateRequest req) {
        return ApiResponse.ok(shotReferenceImageService.generateReferenceImage(projectId, shotId, refId, req));
    }

    @PostMapping("/shots/{shotId:\\d+}/refs/generate-images")
    public ApiResponse<List<WorkspaceDtos.ReferencePromptResponse>> generateShotReferenceImagesBatch(
            @PathVariable Long projectId,
            @PathVariable Long shotId) {
        return ApiResponse.ok(shotReferenceImageService.generateAllReferenceImages(projectId, shotId));
    }

    @GetMapping("/shots/{shotId:\\d+}/refs/{refId:\\d+}/image")
    public ResponseEntity<?> streamShotReferenceImage(
            @PathVariable Long projectId,
            @PathVariable Long shotId,
            @PathVariable Long refId) {
        return shotReferenceImageService.streamReferenceImage(projectId, shotId, refId);
    }

    @GetMapping("/snapshots")
    public ApiResponse<List<WorkspaceDtos.SnapshotResponse>> listSnapshots(@PathVariable Long projectId) {
        return ApiResponse.ok(workspaceService.listSnapshots(projectId));
    }

    @PostMapping("/snapshots")
    public ApiResponse<WorkspaceDtos.SnapshotResponse> createSnapshot(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, String> body) {
        String label = body != null ? body.get("label") : null;
        return ApiResponse.ok(workspaceService.createManualSnapshot(projectId, label));
    }

    @PostMapping("/snapshots/{snapshotId:\\d+}/restore")
    public ApiResponse<Map<String, Object>> restoreSnapshot(
            @PathVariable Long projectId,
            @PathVariable Long snapshotId) {
        return ApiResponse.ok(workspaceService.restoreSnapshot(projectId, snapshotId));
    }

    @PostMapping("/export")
    public ApiResponse<Map<String, Object>> export(@PathVariable Long projectId) {
        return ApiResponse.ok(workspaceService.exportProject(projectId));
    }

    @GetMapping("/shots/{shotId:\\d+}/videos")
    public ApiResponse<List<WorkspaceDtos.ShotVideoResponse>> listShotVideos(
            @PathVariable Long projectId,
            @PathVariable Long shotId) {
        return ApiResponse.ok(videoProductionService.listVideos(projectId, shotId));
    }

    @GetMapping("/shots/{shotId:\\d+}/videos/{videoId:\\d+}/file")
    public ResponseEntity<?> streamShotVideo(
            @PathVariable Long projectId,
            @PathVariable Long shotId,
            @PathVariable Long videoId) {
        return videoProductionService.streamVideo(projectId, shotId, videoId);
    }

    @DeleteMapping("/shots/{shotId:\\d+}/videos/{videoId:\\d+}")
    public ApiResponse<Void> deleteShotVideo(
            @PathVariable Long projectId,
            @PathVariable Long shotId,
            @PathVariable Long videoId) {
        videoProductionService.deleteVideo(projectId, shotId, videoId);
        return ApiResponse.ok(null);
    }
}
