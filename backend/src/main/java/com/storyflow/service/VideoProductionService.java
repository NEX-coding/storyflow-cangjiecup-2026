package com.storyflow.service;

import com.storyflow.common.BusinessException;
import com.storyflow.config.StoryflowProperties;
import com.storyflow.dto.WorkspaceDtos;
import com.storyflow.entity.Shot;
import com.storyflow.entity.ShotVideo;
import com.storyflow.repository.ShotRepository;
import com.storyflow.repository.ShotVideoRepository;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;

/**
 * 分镜视频：仅保留历史成片的列表 / 播放 / 删除。视频生成能力已下线。
 */
@Service
public class VideoProductionService {

    private final ProjectService projectService;
    private final ShotRepository shotRepository;
    private final ShotVideoRepository shotVideoRepository;
    private final MediaVaultService mediaVaultService;
    private final StoryflowProperties properties;
    private final RestClient restClient;
    private final RestClient archiveRestClient;

    public VideoProductionService(
            ProjectService projectService,
            ShotRepository shotRepository,
            ShotVideoRepository shotVideoRepository,
            MediaVaultService mediaVaultService,
            StoryflowProperties properties) {
        this.projectService = projectService;
        this.shotRepository = shotRepository;
        this.shotVideoRepository = shotVideoRepository;
        this.mediaVaultService = mediaVaultService;
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(120_000);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
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

    /** 启动时把未完成的生成任务标失败（生成能力已下线）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void failStaleGeneratingVideos() {
        for (ShotVideo video : shotVideoRepository.findByStatus("generating")) {
            video.setStatus("failed");
            video.setErrorMessage("视频生成功能已下线");
            shotVideoRepository.save(video);
        }
    }

    public List<WorkspaceDtos.ShotVideoResponse> listVideos(Long projectId, Long shotId) {
        ensureShot(projectId, shotId);
        return shotVideoRepository.findByProjectIdAndShotIdOrderByCreatedAtDesc(projectId, shotId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void deleteVideo(Long projectId, Long shotId, Long videoId) {
        projectService.requireWritableProject(projectId);
        ensureShot(projectId, shotId);
        ShotVideo video = shotVideoRepository.findByIdAndProjectIdAndShotId(videoId, projectId, shotId)
                .orElseThrow(() -> new BusinessException("视频不存在"));
        String fileName = video.getRemoteFile();
        shotVideoRepository.delete(video);
        if (fileName != null && !fileName.isBlank()) {
            purgeArchivedVideo(fileName);
        }
    }

    private void purgeArchivedVideo(String fileName) {
        StoryflowProperties.Media media = properties.getMedia();
        if (mediaVaultService.isLocalVaultEnabled()) {
            try {
                mediaVaultService.delete(fileName);
            } catch (Exception ignored) {
                // best-effort
            }
            return;
        }
        String archiveBase = normalizeBase(media.getArchiveBaseUrl());
        if (!archiveBase.isBlank()) {
            try {
                RestClient.RequestHeadersSpec<?> spec = archiveRestClient.delete()
                        .uri(archiveBase + "/videos/" + Path.of(fileName).getFileName());
                applyToken(spec, media.getApiToken());
                spec.retrieve().toBodilessEntity();
            } catch (Exception ignored) {
                // best-effort
            }
            return;
        }
        String h3Base = normalizeBase(media.resolveH3BaseUrl());
        if (!h3Base.isBlank()) {
            deleteH3File(h3Base, fileName, media.getApiToken());
        }
    }

    public ResponseEntity<?> streamVideo(Long projectId, Long shotId, Long videoId) {
        projectService.getProject(projectId);
        ShotVideo video = shotVideoRepository.findByIdAndProjectIdAndShotId(videoId, projectId, shotId)
                .orElseThrow(() -> new BusinessException("视频不存在"));
        if (!"ready".equals(video.getStatus()) || video.getRemoteFile() == null || video.getRemoteFile().isBlank()) {
            throw new BusinessException("视频尚未就绪");
        }

        String fileName = video.getRemoteFile();
        StoryflowProperties.Media media = properties.getMedia();

        if (mediaVaultService.isLocalVaultEnabled()) {
            try {
                return mediaVaultService.open(fileName);
            } catch (BusinessException ignored) {
                // fall through
            }
        }

        String archiveBase = normalizeBase(media.getArchiveBaseUrl());
        if (!archiveBase.isBlank()) {
            RestClient.RequestHeadersSpec<?> spec = archiveRestClient.get()
                    .uri(archiveBase + "/videos/" + fileName);
            applyToken(spec, media.getApiToken());
            return spec.exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    throw new BusinessException("无法从媒体库读取视频");
                }
                byte[] bytes = response.getBody().readAllBytes();
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.parseMediaType("video/mp4"));
                headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"");
                return ResponseEntity.ok().headers(headers).body(bytes);
            });
        }

        String h3Base = normalizeBase(media.resolveH3BaseUrl());
        RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(h3Base + "/videos/" + fileName);
        applyToken(spec, media.getApiToken());
        return spec.exchange((request, response) -> {
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new BusinessException("无法读取视频文件");
            }
            InputStream body = response.getBody();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("video/mp4"));
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"");
            return ResponseEntity.ok().headers(headers).body(new InputStreamResource(body));
        });
    }

    private void deleteH3File(String h3Base, String fileName, String token) {
        try {
            RestClient.RequestHeadersSpec<?> spec = restClient.delete().uri(h3Base + "/videos/" + fileName);
            applyToken(spec, token);
            spec.retrieve().toBodilessEntity();
        } catch (Exception ignored) {
            try {
                Path local = Path.of(System.getProperty("user.home"), "h3-video", "outputs", fileName);
                Files.deleteIfExists(local);
            } catch (Exception ignored2) {
                // ignore
            }
        }
    }

    private Shot ensureShot(Long projectId, Long shotId) {
        Shot shot = shotRepository.findById(shotId)
                .orElseThrow(() -> new BusinessException("分镜不存在"));
        if (!shot.getProjectId().equals(projectId)) {
            throw new BusinessException("分镜不存在");
        }
        return shot;
    }

    private WorkspaceDtos.ShotVideoResponse toResponse(ShotVideo video) {
        WorkspaceDtos.ShotVideoResponse resp = new WorkspaceDtos.ShotVideoResponse();
        resp.setId(video.getId());
        resp.setShotId(video.getShotId());
        resp.setStatus(video.getStatus());
        resp.setPrompt(video.getPrompt());
        resp.setRemoteFile(video.getRemoteFile());
        resp.setErrorMessage(video.getErrorMessage());
        resp.setElapsedSec(video.getElapsedSec());
        resp.setFrames(video.getFrames());
        resp.setWidth(video.getWidth());
        resp.setHeight(video.getHeight());
        resp.setCreatedAt(video.getCreatedAt() != null ? video.getCreatedAt().toString() : null);
        if ("ready".equals(video.getStatus()) && video.getId() != null) {
            resp.setFileUrl(String.format(
                    "/projects/%d/shots/%d/videos/%d/file",
                    video.getProjectId(),
                    video.getShotId(),
                    video.getId()));
        }
        return resp;
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
