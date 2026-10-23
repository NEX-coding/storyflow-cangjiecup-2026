package com.storyflow.service;

import com.storyflow.common.BusinessException;
import com.storyflow.config.StoryflowProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class MediaVaultService {

    private final StoryflowProperties properties;

    public MediaVaultService(StoryflowProperties properties) {
        this.properties = properties;
    }

    public boolean isLocalVaultEnabled() {
        String dir = properties.getMedia().getStorageDir();
        return dir != null && !dir.isBlank();
    }

    /** 已落盘参考图的公网 URL（无鉴权，文件名不可猜测） */
    public String publicAssetUrl(String fileName) {
        if (!isLocalVaultEnabled() || fileName == null || fileName.isBlank()) {
            return null;
        }
        String base = properties.getMedia().getPublicAssetBaseUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        String safe = Path.of(fileName).getFileName().toString();
        String root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return root + "/" + safe;
    }

    public Map<String, String> store(InputStream data, String originalName, String contentType) {
        Path dir = requireStorageDir();
        String safeExt = extensionOf(originalName, contentType);
        String fileName = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + safeExt;
        Path target = dir.resolve(fileName).normalize();
        if (!target.startsWith(dir)) {
            throw new BusinessException("非法文件名");
        }
        try {
            Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException("保存媒体失败: " + e.getMessage());
        }
        return Map.of("ok", "true", "file", fileName);
    }

    public ResponseEntity<Resource> open(String fileName) {
        Path path = resolveExisting(fileName);
        String lower = fileName.toLowerCase(Locale.ROOT);
        MediaType type = lower.endsWith(".png")
                ? MediaType.IMAGE_PNG
                : lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                ? MediaType.IMAGE_JPEG
                : MediaType.parseMediaType("video/mp4");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + path.getFileName() + "\"")
                .contentType(type)
                .body(new FileSystemResource(path));
    }

    public Path resolveExisting(String fileName) {
        Path dir = requireStorageDir();
        Path path = dir.resolve(Path.of(fileName).getFileName().toString()).normalize();
        if (!path.startsWith(dir) || !Files.isRegularFile(path)) {
            throw new BusinessException("媒体文件不存在");
        }
        return path;
    }

    /** 彻底删除本机媒体文件；文件不存在时静默成功。 */
    public boolean delete(String fileName) {
        if (!isLocalVaultEnabled() || fileName == null || fileName.isBlank()) {
            return false;
        }
        Path dir = requireStorageDir();
        Path path = dir.resolve(Path.of(fileName).getFileName().toString()).normalize();
        if (!path.startsWith(dir)) {
            throw new BusinessException("非法文件名");
        }
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new BusinessException("删除媒体失败: " + e.getMessage());
        }
    }

    private Path requireStorageDir() {
        if (!isLocalVaultEnabled()) {
            throw new BusinessException("本机未配置媒体存储目录");
        }
        Path dir = Path.of(properties.getMedia().getStorageDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new BusinessException("无法创建媒体目录: " + e.getMessage());
        }
        return dir;
    }

    private static String extensionOf(String originalName, String contentType) {
        if (originalName != null) {
            String name = Path.of(originalName).getFileName().toString();
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot < name.length() - 1) {
                return name.substring(dot).toLowerCase(Locale.ROOT);
            }
        }
        if (contentType != null) {
            if (contentType.contains("png")) return ".png";
            if (contentType.contains("jpeg") || contentType.contains("jpg")) return ".jpg";
        }
        return ".mp4";
    }
}
