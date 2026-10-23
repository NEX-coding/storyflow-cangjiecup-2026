package com.storyflow.service;

import com.storyflow.ai.AspectRatioSupport;
import com.storyflow.ai.ProjectSettingsSupport;
import com.storyflow.ai.PromptComposeSupport;
import com.storyflow.common.BusinessException;
import com.storyflow.config.StoryflowProperties;
import com.storyflow.dto.WorkspaceDtos;
import com.storyflow.entity.Asset;
import com.storyflow.entity.AssetType;
import com.storyflow.entity.Project;
import com.storyflow.entity.ReferencePrompt;
import com.storyflow.entity.Shot;
import com.storyflow.repository.AssetRepository;
import com.storyflow.repository.ShotRepository;
import com.storyflow.util.JsonUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class ShotReferenceImageService {

    private final ProjectService projectService;
    private final ShotRepository shotRepository;
    private final AssetRepository assetRepository;
    private final AssetImageService assetImageService;
    private final MediaVaultService mediaVaultService;
    private final PromptComposeSupport promptCompose;
    private final ProjectSettingsSupport projectSettingsSupport;
    private final JsonUtil jsonUtil;
    private final RestClient restClient;

    public ShotReferenceImageService(
            ProjectService projectService,
            ShotRepository shotRepository,
            AssetRepository assetRepository,
            AssetImageService assetImageService,
            MediaVaultService mediaVaultService,
            PromptComposeSupport promptCompose,
            ProjectSettingsSupport projectSettingsSupport,
            JsonUtil jsonUtil,
            StoryflowProperties properties) {
        this.projectService = projectService;
        this.shotRepository = shotRepository;
        this.assetRepository = assetRepository;
        this.assetImageService = assetImageService;
        this.mediaVaultService = mediaVaultService;
        this.promptCompose = promptCompose;
        this.projectSettingsSupport = projectSettingsSupport;
        this.jsonUtil = jsonUtil;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(300_000);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /** 生图走 GPU，勿包长事务（否则易拖垮连接池 / 被 nginx 掐断）。 */
    public WorkspaceDtos.ReferencePromptResponse generateReferenceImage(
            Long projectId,
            Long shotId,
            Long refId,
            WorkspaceDtos.ReferenceImageGenerateRequest req) {
        Project project = projectService.requireWritableProject(projectId);
        Shot shot = ensureShot(projectId, shotId);
        ReferencePrompt ref = ensureRef(shot, refId);

        var settings = projectSettingsSupport.read(project);
        String refMode = resolveRefMode(project, shot);
        boolean flfStill = ProjectSettingsSupport.MODE_FIRST_LAST.equals(refMode);
        List<Asset> linked = resolveLinkedAssets(shot);
        boolean customPrompt = req != null && req.getPrompt() != null && !req.getPrompt().isBlank();
        String prompt;
        if (customPrompt) {
            // 首尾帧静帧：去运镜前置；单图故事版走普通 SHOT_GPU
            prompt = flfStill
                    ? promptCompose.finalizeStillForGpu(
                            req.getPrompt().trim(),
                            project.getStylePreset(),
                            linked,
                            settings.styleReferenceNote())
                    : promptCompose.finalizeForGpu(
                            com.storyflow.ai.PromptPipeline.Target.SHOT_GPU,
                            req.getPrompt().trim(),
                            project.getStylePreset(),
                            linked,
                            settings.styleReferenceNote());
        } else {
            prompt = buildComposedPrompt(ref, shot, project);
        }
        if (prompt.isBlank()) {
            throw new BusinessException("请先填写参考图提示词");
        }

        StoryflowProperties.Media media = assetImageService.getMediaProperties();
        var sized = AspectRatioSupport.resolveImageSize(
                settings.refImageAspectRatio(), media);
        int width = req != null && req.getWidth() != null ? req.getWidth() : sized.width();
        int height = req != null && req.getHeight() != null ? req.getHeight() : sized.height();
        Integer steps = req != null ? req.getNumInferenceSteps() : null;

        // 首尾帧静帧：禁止用人设三视图/白底设定板做 img2img init——会渗白底半抠图（开源 FLF 亦是文生静帧+文字锁身份）
        // 单图·故事版：仍可用 1 张主角参考，strength 偏高让版式服从提示词
        List<String> assetRefB64 = flfStill
                ? List.of()
                : loadLinkedAssetImageBase64(linked);
        Double strength = ProjectSettingsSupport.MODE_SINGLE.equals(refMode) ? 0.82 : 0.62;
        String archived = assetRefB64.isEmpty()
                ? assetImageService.generateAndArchiveImage(prompt, width, height, steps)
                : assetImageService.generateAndArchiveImage(prompt, width, height, steps, assetRefB64, strength);
        ref.setReferenceImageFile(archived);
        shotRepository.save(shot);
        return toResponse(ref, projectId, shotId);
    }

    /**
     * 故事版图生图：只取 1 张主角参考（避免把人设三视图+道具产品图拼进 init，导致输出成设定拼贴）。
     */
    private List<String> loadLinkedAssetImageBase64(List<Asset> linked) {
        if (linked == null || linked.isEmpty()) {
            return List.of();
        }
        List<Asset> ordered = linked.stream()
                .filter(a -> a.getReferenceImageFile() != null && !a.getReferenceImageFile().isBlank())
                .sorted(Comparator.comparingInt(a -> assetTypePriority(a.getType())))
                .limit(1)
                .toList();
        List<String> out = new ArrayList<>();
        for (Asset asset : ordered) {
            String b64 = assetImageService.loadArchivedImageBase64(asset.getReferenceImageFile());
            if (b64 != null && !b64.isBlank()) {
                out.add(b64);
            }
        }
        return out;
    }

    private static int assetTypePriority(AssetType type) {
        if (type == null) {
            return 9;
        }
        return switch (type) {
            case character -> 0;
            case scene -> 1;
            case prop -> 2;
        };
    }

    @Transactional
    public List<WorkspaceDtos.ReferencePromptResponse> generateAllReferenceImages(
            Long projectId, Long shotId) {
        projectService.requireWritableProject(projectId);
        Shot shot = ensureShot(projectId, shotId);
        if (shot.getReferencePrompts().isEmpty()) {
            throw new BusinessException("暂无参考图提示词");
        }
        List<WorkspaceDtos.ReferencePromptResponse> results = new ArrayList<>();
        for (ReferencePrompt ref : shot.getReferencePrompts()) {
            WorkspaceDtos.ReferenceImageGenerateRequest single = new WorkspaceDtos.ReferenceImageGenerateRequest();
            results.add(generateReferenceImage(projectId, shotId, ref.getId(), single));
        }
        return results;
    }

    public ResponseEntity<?> streamReferenceImage(Long projectId, Long shotId, Long refId) {
        projectService.getProject(projectId);
        Shot shot = ensureShot(projectId, shotId);
        ReferencePrompt ref = ensureRef(shot, refId);
        if (ref.getReferenceImageFile() == null || ref.getReferenceImageFile().isBlank()) {
            throw new BusinessException("分镜参考图尚未生成");
        }

        String fileName = ref.getReferenceImageFile();
        if (mediaVaultService.isLocalVaultEnabled()) {
            try {
                return mediaVaultService.open(fileName);
            } catch (BusinessException ignored) {
                // fall through
            }
        }

        StoryflowProperties.Media media = assetImageService.getMediaProperties();
        String archiveBase = normalizeBase(media.getArchiveBaseUrl());
        if (archiveBase.isBlank()) {
            throw new BusinessException("分镜参考图不可用：未配置本机媒体目录或归档地址");
        }
        RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(archiveBase + "/images/" + fileName);
        applyToken(spec, media.getApiToken());
        return spec.exchange((request, response) -> {
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new BusinessException("无法读取分镜参考图");
            }
            byte[] bytes = response.getBody().readAllBytes();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"");
            return ResponseEntity.ok().headers(headers).body(bytes);
        });
    }

    public WorkspaceDtos.ReferencePromptResponse toResponse(ReferencePrompt ref, Long projectId, Long shotId) {
        WorkspaceDtos.ReferencePromptResponse resp = new WorkspaceDtos.ReferencePromptResponse();
        resp.setId(ref.getId());
        resp.setType(ref.getType());
        resp.setPrompt(ref.getPrompt());
        resp.setNote(ref.getNote());
        resp.setOrderIndex(ref.getOrderIndex());
        resp.setReferenceImageFile(ref.getReferenceImageFile());
        if (ref.getReferenceImageFile() != null && !ref.getReferenceImageFile().isBlank()) {
            String publicUrl = mediaVaultService.publicAssetUrl(ref.getReferenceImageFile());
            resp.setReferenceImageUrl(publicUrl != null
                    ? publicUrl
                    : String.format("/projects/%d/shots/%d/refs/%d/image", projectId, shotId, ref.getId()));
        }
        return resp;
    }

    /** 参考图提示词 + (@关联资产) 一致性约束；单图模式注入人物外形×剧本合成约束 */
    String buildComposedPrompt(ReferencePrompt ref, Shot shot, Project project) {
        List<Asset> linked = resolveLinkedAssets(shot);
        String mode = resolveRefMode(project, shot);
        boolean singleMain = ProjectSettingsSupport.MODE_SINGLE.equals(mode);
        var settings = projectSettingsSupport.read(project);
        return promptCompose.composeReferencePrompt(
                ref.getPrompt(),
                ref.getNote(),
                ref.getType(),
                linked,
                project.getStylePreset(),
                singleMain,
                settings.styleReferenceNote());
    }

    private String resolveRefMode(Project project, Shot shot) {
        if (shot != null && shot.getVideoRefMode() != null && !shot.getVideoRefMode().isBlank()) {
            return ProjectSettingsSupport.normalizeMode(shot.getVideoRefMode());
        }
        return projectSettingsSupport.read(project).normalizedRefPromptMode();
    }

    private List<Asset> resolveLinkedAssets(Shot shot) {
        List<Long> linkedIds = jsonUtil.parseLongList(shot.getLinkedAssetIdsJson());
        if (linkedIds.isEmpty()) {
            return List.of();
        }
        List<Asset> assets = assetRepository.findByProjectIdOrderByTypeAscNameAsc(shot.getProjectId());
        List<Asset> linked = new ArrayList<>();
        for (Asset asset : assets) {
            if (linkedIds.contains(asset.getId())) {
                linked.add(asset);
            }
        }
        return linked;
    }

    private Shot ensureShot(Long projectId, Long shotId) {
        return shotRepository.findByIdAndProjectIdWithRefs(shotId, projectId)
                .orElseThrow(() -> new BusinessException("分镜不存在"));
    }

    private ReferencePrompt ensureRef(Shot shot, Long refId) {
        return shot.getReferencePrompts().stream()
                .filter(r -> r.getId().equals(refId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("参考图提示词不存在"));
    }

    private static String normalizeBase(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static void applyToken(RestClient.RequestHeadersSpec<?> spec, String token) {
        if (token != null && !token.isBlank()) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token.trim());
        }
    }

}
