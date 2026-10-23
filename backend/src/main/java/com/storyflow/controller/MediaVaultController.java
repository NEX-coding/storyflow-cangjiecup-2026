package com.storyflow.controller;

import com.storyflow.common.ApiResponse;
import com.storyflow.common.BusinessException;
import com.storyflow.config.StoryflowProperties;
import com.storyflow.service.MediaVaultService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 媒体保险库：成片/图片落盘到 edu 服务器，供 GPU 后端归档与回源播放。
 * 鉴权：Authorization Bearer = storyflow.media.api-token
 */
@RestController
@RequestMapping("/api/internal/media")
public class MediaVaultController {

    private final MediaVaultService mediaVaultService;
    private final StoryflowProperties properties;

    public MediaVaultController(MediaVaultService mediaVaultService, StoryflowProperties properties) {
        this.mediaVaultService = mediaVaultService;
        this.properties = properties;
    }

    @PostMapping("/videos")
    public ApiResponse<Map<String, String>> uploadVideo(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam("file") MultipartFile file) throws IOException {
        requireToken(authorization);
        if (file == null || file.isEmpty()) {
            throw new BusinessException("空文件");
        }
        return ApiResponse.ok(mediaVaultService.store(
                file.getInputStream(),
                file.getOriginalFilename(),
                file.getContentType()));
    }

    @GetMapping("/videos/{fileName}")
    public ResponseEntity<Resource> getVideo(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String fileName) {
        requireToken(authorization);
        return mediaVaultService.open(fileName);
    }

    @DeleteMapping("/videos/{fileName}")
    public ApiResponse<Map<String, Object>> deleteVideo(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String fileName) {
        requireToken(authorization);
        boolean deleted = mediaVaultService.delete(fileName);
        return ApiResponse.ok(Map.of("ok", true, "deleted", deleted));
    }

    @PostMapping("/images")
    public ApiResponse<Map<String, String>> uploadImage(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam("file") MultipartFile file) throws IOException {
        requireToken(authorization);
        if (file == null || file.isEmpty()) {
            throw new BusinessException("空文件");
        }
        return ApiResponse.ok(mediaVaultService.store(
                file.getInputStream(),
                file.getOriginalFilename(),
                file.getContentType() != null ? file.getContentType() : "image/png"));
    }

    @GetMapping("/images/{fileName}")
    public ResponseEntity<Resource> getImage(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String fileName) {
        requireToken(authorization);
        return mediaVaultService.open(fileName);
    }

    @DeleteMapping("/images/{fileName}")
    public ApiResponse<Map<String, Object>> deleteImage(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String fileName) {
        requireToken(authorization);
        boolean deleted = mediaVaultService.delete(fileName);
        return ApiResponse.ok(Map.of("ok", true, "deleted", deleted));
    }

    private void requireToken(String authorization) {
        String expected = properties.getMedia().getApiToken();
        if (expected == null || expected.isBlank()) {
            throw new BusinessException("媒体库未配置 token");
        }
        String got = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim()
                : "";
        if (!expected.equals(got)) {
            throw new BusinessException("unauthorized");
        }
    }
}
