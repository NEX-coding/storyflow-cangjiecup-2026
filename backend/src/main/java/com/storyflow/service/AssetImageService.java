package com.storyflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyflow.ai.AspectRatioSupport;
import com.storyflow.ai.OllamaClient;
import com.storyflow.ai.ProjectSettingsSupport;
import com.storyflow.ai.PromptComposeSupport;
import com.storyflow.common.BusinessException;
import com.storyflow.config.StoryflowProperties;
import com.storyflow.dto.WorkspaceDtos;
import com.storyflow.entity.Asset;
import com.storyflow.entity.Project;
import com.storyflow.repository.AssetRepository;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Service
public class AssetImageService {

    private final ProjectService projectService;
    private final AssetRepository assetRepository;
    private final MediaVaultService mediaVaultService;
    private final PromptComposeSupport promptCompose;
    private final ProjectSettingsSupport projectSettingsSupport;
    private final StoryflowProperties properties;
    private final OllamaClient ollamaClient;
    private final RestClient archiveRestClient;
    private final ObjectMapper objectMapper;
    /** 本机 Ollama 生图串行，避免多任务挤爆显存 */
    private final Object imageGenerateLock = new Object();

    public AssetImageService(
            ProjectService projectService,
            AssetRepository assetRepository,
            MediaVaultService mediaVaultService,
            PromptComposeSupport promptCompose,
            ProjectSettingsSupport projectSettingsSupport,
            StoryflowProperties properties,
            OllamaClient ollamaClient,
            ObjectMapper objectMapper) {
        this.projectService = projectService;
        this.assetRepository = assetRepository;
        this.mediaVaultService = mediaVaultService;
        this.promptCompose = promptCompose;
        this.projectSettingsSupport = projectSettingsSupport;
        this.properties = properties;
        this.ollamaClient = ollamaClient;
        this.objectMapper = objectMapper;
        this.archiveRestClient = buildArchiveRestClient();
    }

    private static RestClient buildArchiveRestClient() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] c, String a) {}
                        public void checkServerTrusted(X509Certificate[] c, String a) {}
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            };
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, trustAll, new java.security.SecureRandom());
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .sslContext(ssl)
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(Duration.ofMinutes(5));
            return RestClient.builder().requestFactory(factory).build();
        } catch (Exception e) {
            return RestClient.create();
        }
    }

    /** Ollama 生图并归档，供资产图 / 分镜参考图共用（串行锁） */
    public String generateAndArchiveImage(String prompt, Integer width, Integer height, Integer steps) {
        return generateAndArchiveImage(prompt, width, height, steps, null, null);
    }

    /**
     * Ollama（x/flux2-klein）生图；若 {@code referenceImagesB64} 非空则带参考图做编辑。
     *
     * @param referenceImagesB64 资产参考图像 base64（可多张）
     * @param strength           保留参数兼容旧调用，当前 Ollama 路径忽略
     */
    public String generateAndArchiveImage(
            String prompt,
            Integer width,
            Integer height,
            Integer steps,
            List<String> referenceImagesB64,
            Double strength) {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException("生图提示词不能为空");
        }
        StoryflowProperties.Media media = properties.getMedia();
        int w = width != null ? width : media.getImageDefaultWidth();
        int h = height != null ? height : media.getImageDefaultHeight();

        try {
            List<String> refs = null;
            if (referenceImagesB64 != null && !referenceImagesB64.isEmpty()) {
                // 压小体积，降低本机 Ollama 图生图内存压力
                String initB64 = compressRefsToSingleInitJpeg(referenceImagesB64);
                refs = initB64 != null ? List.of(initB64) : referenceImagesB64;
            }
            int stepCount = steps != null && steps > 0 ? steps : Math.max(8, media.getImageDefaultSteps());
            byte[] bytes;
            synchronized (imageGenerateLock) {
                bytes = ollamaClient.generateImage(prompt.trim(), w, h, stepCount, refs);
            }
            if (bytes == null || bytes.length == 0) {
                throw new BusinessException("图片生成失败：空结果");
            }
            String fileName = "flux_" + System.currentTimeMillis() + ".png";
            return archiveBytes(bytes, fileName, media);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "未知错误";
            if (msg.toLowerCase().contains("unavailable")
                    || msg.toLowerCase().contains("not reachable")
                    || msg.contains("503")
                    || msg.contains("Connection refused")) {
                throw new BusinessException("生图服务不可用，请确认本机 Ollama 已加载 "
                        + ollamaClient.getImageModelName() + ": " + msg);
            }
            throw new BusinessException("图片生成失败: " + msg);
        }
    }

    /**
     * 单张参考图压成 JPEG base64，供图生视频 first_frame（控制 FRP body 体积）。
     * 失败返回 null（禁止回退原图，避免 1～2MB PNG 堵死 H3/nginx）。
     */
    public static String compressImageBytesToJpegBase64(byte[] bytes, int maxSide) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
            if (src == null) {
                return null;
            }
            // 图生视频首帧：控制在 ~150KB，过大易导致 FRP 超时 / H3 卡死
            int side = Math.min(Math.max(512, maxSide), 960);
            BufferedImage scaled = scaleToMax(src, side);
            float quality = 0.70f;
            byte[] jpeg = encodeJpeg(scaled, quality);
            int guard = 0;
            while (jpeg.length > 150_000 && guard < 8) {
                quality = Math.max(0.40f, quality - 0.08f);
                scaled = scaleToMax(scaled, Math.max(480, scaled.getWidth() * 4 / 5));
                jpeg = encodeJpeg(scaled, quality);
                guard++;
            }
            return Base64.getEncoder().encodeToString(jpeg);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 将多张资产参考图缩放到小图并横拼为单张 JPEG（目标 &lt; ~450KB），供 FRP 图生图。
     * 失败或无图返回 null。
     */
    static String compressRefsToSingleInitJpeg(List<String> referenceImagesB64) {
        if (referenceImagesB64 == null || referenceImagesB64.isEmpty()) {
            return null;
        }
        try {
            List<BufferedImage> cells = new ArrayList<>();
            int maxCell = 384;
            for (String b64 : referenceImagesB64) {
                if (b64 == null || b64.isBlank() || cells.size() >= 3) {
                    continue;
                }
                byte[] raw = Base64.getDecoder().decode(stripDataUrl(b64));
                BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
                if (src == null) {
                    continue;
                }
                cells.add(scaleToMax(src, maxCell));
            }
            if (cells.isEmpty()) {
                return null;
            }
            int cellH = cells.stream().mapToInt(BufferedImage::getHeight).max().orElse(maxCell);
            int totalW = cells.stream().mapToInt(BufferedImage::getWidth).sum();
            BufferedImage canvas = new BufferedImage(totalW, cellH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = canvas.createGraphics();
            g.setColor(new Color(245, 245, 245));
            g.fillRect(0, 0, totalW, cellH);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int x = 0;
            for (BufferedImage cell : cells) {
                int y = (cellH - cell.getHeight()) / 2;
                g.drawImage(cell, x, y, null);
                x += cell.getWidth();
            }
            g.dispose();

            float quality = 0.72f;
            byte[] jpeg = encodeJpeg(canvas, quality);
            // GPU nginx 大 body 会落盘；控制在 ~120KB 更稳
            int guard = 0;
            while (jpeg.length > 120_000 && guard < 6) {
                quality = Math.max(0.40f, quality - 0.10f);
                canvas = scaleToMax(canvas, Math.max(220, canvas.getWidth() * 4 / 5));
                jpeg = encodeJpeg(canvas, quality);
                guard++;
            }
            return Base64.getEncoder().encodeToString(jpeg);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String stripDataUrl(String b64) {
        String s = b64.trim();
        int comma = s.indexOf(',');
        if (comma > 0 && s.substring(0, comma).toLowerCase().contains("base64")) {
            return s.substring(comma + 1);
        }
        return s;
    }

    private static BufferedImage scaleToMax(BufferedImage src, int maxSide) {
        int w = src.getWidth();
        int h = src.getHeight();
        int side = Math.max(w, h);
        if (side <= maxSide) {
            if (src.getType() == BufferedImage.TYPE_INT_RGB) {
                return src;
            }
            BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(src, 0, 0, Color.WHITE, null);
            g.dispose();
            return rgb;
        }
        double scale = (double) maxSide / side;
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, Color.WHITE, null);
        g.dispose();
        return out;
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ImageIO.write(image, "jpg", baos);
            return baos.toByteArray();
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(Math.max(0.3f, Math.min(0.95f, quality)));
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    /** 从本机 vault / 远程归档读取已存图片为 base64；失败返回 null。 */
    public String loadArchivedImageBase64(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = null;
            if (mediaVaultService.isLocalVaultEnabled()) {
                try {
                    bytes = Files.readAllBytes(mediaVaultService.resolveExisting(fileName));
                } catch (BusinessException ignored) {
                    // fall through
                }
            }
            if (bytes == null) {
                StoryflowProperties.Media media = properties.getMedia();
                String archiveBase = normalizeBase(media.getArchiveBaseUrl());
                if (!archiveBase.isBlank()) {
                    RestClient.RequestHeadersSpec<?> spec = archiveRestClient.get()
                            .uri(archiveBase + "/images/" + Path.of(fileName).getFileName())
                            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_OCTET_STREAM_VALUE);
                    applyToken(spec, media.getApiToken());
                    bytes = spec.exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                            return null;
                        }
                        return response.getBody().readAllBytes();
                    });
                }
            }
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception ex) {
            return null;
        }
    }

    @Transactional
    public WorkspaceDtos.AssetResponse generateImage(
            Long projectId, Long assetId, WorkspaceDtos.AssetImageGenerateRequest req) {
        Project project = projectService.requireWritableProject(projectId);
        Asset asset = ensureAsset(projectId, assetId);
        String prompt = req != null && req.getPrompt() != null && !req.getPrompt().isBlank()
                ? req.getPrompt().trim()
                : nullToEmpty(asset.getImagePrompt()).trim();
        if (prompt.isEmpty()) {
            throw new BusinessException("请先填写或生成生图提示词");
        }
        // 送 GPU 前包装：画风默认前后缀 + 风格参考描述，不改库内原文
        var settings = projectSettingsSupport.read(project);
        prompt = promptCompose.wrapAssetImagePrompt(
                prompt,
                asset.getType(),
                project.getStylePreset(),
                settings.styleFor(asset.getType()),
                settings.styleReferenceNote());

        StoryflowProperties.Media media = properties.getMedia();
        var sized = AspectRatioSupport.resolveImageSize(
                projectSettingsSupport.read(project).assetImageAspectRatio(), media);
        int width = req != null && req.getWidth() != null ? req.getWidth() : sized.width();
        int height = req != null && req.getHeight() != null ? req.getHeight() : sized.height();
        int steps = req != null && req.getNumInferenceSteps() != null
                ? req.getNumInferenceSteps()
                : media.getImageDefaultSteps();

        String archived = generateAndArchiveImage(prompt, width, height, steps);
        asset.setReferenceImageFile(archived);
        assetRepository.save(asset);
        return toResponse(asset);
    }

    @Transactional
    public List<WorkspaceDtos.AssetResponse> generateImagesBatch(
            Long projectId, WorkspaceDtos.AssetImageBatchRequest req) {
        projectService.requireWritableProject(projectId);
        List<Asset> assets = assetRepository.findByProjectIdOrderByTypeAscNameAsc(projectId);
        if (assets.isEmpty()) {
            throw new BusinessException("暂无资产可生成参考图");
        }

        List<Long> targetIds = req != null ? req.getAssetIds() : null;
        List<Asset> targets = new ArrayList<>();
        for (Asset asset : assets) {
            if (targetIds == null || targetIds.isEmpty() || targetIds.contains(asset.getId())) {
                targets.add(asset);
            }
        }
        if (targets.isEmpty()) {
            throw new BusinessException("未找到指定资产");
        }

        List<WorkspaceDtos.AssetResponse> results = new ArrayList<>();
        for (Asset asset : targets) {
            WorkspaceDtos.AssetImageGenerateRequest single = new WorkspaceDtos.AssetImageGenerateRequest();
            results.add(generateImage(projectId, asset.getId(), single));
        }
        return results;
    }

    public ResponseEntity<?> streamImage(Long projectId, Long assetId) {
        projectService.getProject(projectId);
        Asset asset = ensureAsset(projectId, assetId);
        if (asset.getReferenceImageFile() == null || asset.getReferenceImageFile().isBlank()) {
            throw new BusinessException("参考图尚未生成");
        }

        String fileName = asset.getReferenceImageFile();
        StoryflowProperties.Media media = properties.getMedia();

        if (mediaVaultService.isLocalVaultEnabled()) {
            try {
                return mediaVaultService.open(fileName);
            } catch (BusinessException ignored) {
                // fall through
            }
        }

        String archiveBase = normalizeBase(media.getArchiveBaseUrl());
        if (archiveBase.isBlank()) {
            throw new BusinessException("参考图不可用：未配置本机媒体目录或归档地址");
        }
        RestClient.RequestHeadersSpec<?> spec = archiveRestClient.get()
                .uri(archiveBase + "/images/" + fileName);
        applyToken(spec, media.getApiToken());
        return spec.exchange((request, response) -> {
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new BusinessException("无法从媒体库读取参考图");
            }
            byte[] bytes = response.getBody().readAllBytes();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"");
            return ResponseEntity.ok().headers(headers).body(bytes);
        });
    }

    public StoryflowProperties.Media getMediaProperties() {
        return properties.getMedia();
    }

    public WorkspaceDtos.AssetResponse toResponse(Asset asset) {
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

    private String archiveBytes(byte[] bytes, String originalName, StoryflowProperties.Media media) {
        if (mediaVaultService.isLocalVaultEnabled()) {
            try {
                return mediaVaultService.store(
                                new java.io.ByteArrayInputStream(bytes), originalName, "image/png")
                        .get("file");
            } catch (Exception e) {
                throw new BusinessException("写入本机媒体库失败: " + e.getMessage());
            }
        }

        String archiveBase = normalizeBase(media.getArchiveBaseUrl());
        if (archiveBase.isBlank()) {
            throw new BusinessException("未配置媒体归档地址（storyflow.media.archive-base-url）");
        }

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return originalName != null ? originalName : "reference.png";
            }
        }).contentType(MediaType.IMAGE_PNG);

        RestClient.RequestBodySpec spec = archiveRestClient.post()
                .uri(archiveBase + "/images")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(builder.build());
        applyToken(spec, media.getApiToken());

        String raw = spec.retrieve().body(String.class);
        try {
            JsonNode json = objectMapper.readTree(raw);
            JsonNode data = json.has("data") ? json.get("data") : json;
            String file = data.path("file").asText("");
            if (file.isBlank()) {
                throw new BusinessException("媒体库未返回文件名");
            }
            return file;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("归档到 edu 服务器失败: " + e.getMessage());
        }
    }

    private Asset ensureAsset(Long projectId, Long assetId) {
        return assetRepository.findByIdAndProjectId(assetId, projectId)
                .orElseThrow(() -> new BusinessException("资产不存在"));
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

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
