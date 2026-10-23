package com.storyflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyflow.ai.OllamaClient;
import com.storyflow.common.BusinessException;
import com.storyflow.config.StoryflowProperties;
import com.storyflow.dto.AdminUserDtos;
import com.storyflow.entity.AiModelConfig;
import com.storyflow.entity.PromptTemplate;
import com.storyflow.entity.User;
import com.storyflow.entity.UserRole;
import com.storyflow.repository.AiModelConfigRepository;
import com.storyflow.repository.AiUsageLogRepository;
import com.storyflow.repository.AssistantMessageRepository;
import com.storyflow.repository.AuthSessionRepository;
import com.storyflow.repository.FeedbackRepository;
import com.storyflow.repository.ProjectRepository;
import com.storyflow.repository.PromptTemplateRepository;
import com.storyflow.repository.UserRepository;
import lombok.Data;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class AdminService {

    private final ProjectRepository projectRepository;
    private final AssistantMessageRepository assistantMessageRepository;
    private final AiUsageLogRepository aiUsageLogRepository;
    private final PromptTemplateRepository promptTemplateRepository;
    private final AiModelConfigRepository aiModelConfigRepository;
    private final UserRepository userRepository;
    private final AuthSessionRepository authSessionRepository;
    private final FeedbackRepository feedbackRepository;
    private final OllamaClient ollamaClient;
    private final ObjectMapper objectMapper;
    private final CurrentUserService currentUserService;
    private final StoryflowProperties properties;
    private final RestClient probeClient;

    public AdminService(ProjectRepository projectRepository,
                        AssistantMessageRepository assistantMessageRepository,
                        AiUsageLogRepository aiUsageLogRepository,
                        PromptTemplateRepository promptTemplateRepository,
                        AiModelConfigRepository aiModelConfigRepository,
                        UserRepository userRepository,
                        AuthSessionRepository authSessionRepository,
                        FeedbackRepository feedbackRepository,
                        OllamaClient ollamaClient, ObjectMapper objectMapper,
                        CurrentUserService currentUserService,
                        StoryflowProperties properties) {
        this.projectRepository = projectRepository;
        this.assistantMessageRepository = assistantMessageRepository;
        this.aiUsageLogRepository = aiUsageLogRepository;
        this.promptTemplateRepository = promptTemplateRepository;
        this.aiModelConfigRepository = aiModelConfigRepository;
        this.userRepository = userRepository;
        this.authSessionRepository = authSessionRepository;
        this.feedbackRepository = feedbackRepository;
        this.ollamaClient = ollamaClient;
        this.objectMapper = objectMapper;
        this.currentUserService = currentUserService;
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(4000);
        this.probeClient = RestClient.builder().requestFactory(factory).build();
    }

    private void requireAdmin() {
        if (currentUserService.isGuest()) {
            throw new BusinessException(403, "需要管理员权限");
        }
        var user = currentUserService.getRequestUser();
        if (user == null || user.getRole() != UserRole.ADMIN) {
            throw new BusinessException(403, "需要管理员权限");
        }
    }

    public Map<String, Object> dashboard() {
        requireAdmin();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        Map<String, Object> data = new HashMap<>();
        data.put("userCount", userRepository.countByRole(UserRole.USER));
        data.put("activeUserCount", userRepository.countByEnabledTrue());
        data.put("projectCount", projectRepository.count());
        data.put("aiCallCount", assistantMessageRepository.count());
        data.put("todayAiCallCount", aiUsageLogRepository.countByCreatedAtAfter(todayStart));
        data.put("templateCount", promptTemplateRepository.count());
        data.put("feedbackCount", feedbackRepository.count());
        data.put("pendingFeedbackCount", feedbackRepository.countByStatus("pending"));
        return data;
    }

    public AdminUserDtos.UserPage listUsers(String q, String role, Boolean enabled, int page, int size) {
        requireAdmin();
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        UserRole roleFilter = parseRole(role);
        String keyword = q == null ? "" : q.trim();

        Page<User> result = userRepository.search(
                keyword,
                roleFilter,
                enabled,
                PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));

        AdminUserDtos.UserPage body = new AdminUserDtos.UserPage();
        body.setItems(result.getContent().stream().map(this::toUserSummary).toList());
        body.setTotal(result.getTotalElements());
        body.setPage(safePage);
        body.setSize(safeSize);
        return body;
    }

    public AdminUserDtos.UserSummary getUser(Long id) {
        requireAdmin();
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));
        return toUserSummary(user);
    }

    @Transactional
    public AdminUserDtos.UserSummary updateUserStatus(Long id, boolean enabled) {
        requireAdmin();
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));
        if (user.getRole() == UserRole.ADMIN) {
            throw new BusinessException("不能停用管理员账号");
        }
        if (isSystemAccount(user)) {
            throw new BusinessException("不能停用系统内置账号");
        }
        User current = currentUserService.getRequestUser();
        if (current != null && Objects.equals(current.getId(), user.getId())) {
            throw new BusinessException("不能停用当前登录账号");
        }
        user.setEnabled(enabled);
        userRepository.save(user);
        if (!enabled) {
            authSessionRepository.deleteByUserId(user.getId());
        }
        return toUserSummary(user);
    }

    public List<PromptTemplate> listTemplates() {
        requireAdmin();
        return promptTemplateRepository.findAllByOrderByCategoryAscVersionDesc();
    }

    @Transactional
    public PromptTemplate saveTemplate(PromptTemplate template) {
        requireAdmin();
        if (template.getCategory() == null || template.getCategory().isBlank()) {
            throw new BusinessException("分类不能为空");
        }
        if (template.getContent() == null || template.getContent().isBlank()) {
            throw new BusinessException("模板内容不能为空");
        }
        if (template.getName() == null || template.getName().isBlank()) {
            template.setName(template.getCategory());
        }
        return promptTemplateRepository.save(template);
    }

    public AiModelConfig getAiConfig() {
        requireAdmin();
        return aiModelConfigRepository.findFirstByActiveTrueOrderByIdDesc().orElse(null);
    }

    @Transactional
    public AiModelConfig saveAiConfig(AiConfigRequest request) {
        requireAdmin();
        AiModelConfig config = aiModelConfigRepository.findFirstByActiveTrueOrderByIdDesc()
                .orElse(new AiModelConfig());
        config.setProvider(request.getProvider() != null && !request.getProvider().isBlank()
                ? request.getProvider() : "ollama");
        config.setBaseUrl(request.getBaseUrl());
        config.setModelName(request.getModelName());
        try {
            Map<String, Object> extra = new HashMap<>();
            extra.put("temperature", request.getTemperature());
            extra.put("maxTokens", request.getMaxTokens());
            if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
                extra.put("apiKey", request.getApiKey());
            }
            config.setConfigJson(objectMapper.writeValueAsString(extra));
        } catch (Exception ignored) {
        }
        config.setActive(true);
        return aiModelConfigRepository.save(config);
    }

    public Map<String, Object> aiHealth() {
        requireAdmin();
        Map<String, Object> health = new HashMap<>();
        health.put("online", ollamaClient.healthCheck());
        health.put("modelAvailable", ollamaClient.modelAvailable());
        health.put("activeBaseUrl", ollamaClient.getBaseUrl());
        health.put("activeModel", ollamaClient.getModelName());
        health.put("endpoints", ollamaClient.endpointsStatus());
        AiModelConfig config = getAiConfig();
        if (config != null) {
            health.put("baseUrl", config.getBaseUrl());
            health.put("model", config.getModelName());
        } else {
            health.put("baseUrl", ollamaClient.getBaseUrl());
            health.put("model", ollamaClient.getPrimaryModelName());
        }
        return health;
    }

    /** 生图 / 视频媒体服务当前生效配置（token 脱敏） */
    public Map<String, Object> getMediaConfig() {
        requireAdmin();
        return mediaConfigView(properties.getMedia());
    }

    /**
     * 运行时更新媒体服务配置（重启后回退到 .env / yml）。
     * apiToken 传空或 "***" 表示保持原值。
     */
    public Map<String, Object> saveMediaConfig(MediaConfigRequest request) {
        requireAdmin();
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        StoryflowProperties.Media media = properties.getMedia();
        // imageBaseUrl 已废弃（生图走 Ollama）；忽略旧字段写入
        if (request.getImageModel() != null && !request.getImageModel().isBlank()) {
            properties.getOllama().setImageModel(request.getImageModel().trim());
        }
        if (request.getOllamaImageBaseUrl() != null && !request.getOllamaImageBaseUrl().isBlank()) {
            properties.getOllama().setImageBaseUrl(request.getOllamaImageBaseUrl().trim());
        }
        if (request.getH3BaseUrl() != null && !request.getH3BaseUrl().isBlank()) {
            media.setH3BaseUrl(request.getH3BaseUrl().trim());
        }
        // 视频生成已下线：忽略 videoProvider / geeknow / mlx 写入
        if (request.getApiToken() != null && !request.getApiToken().isBlank()
                && !isMaskedToken(request.getApiToken())) {
            media.setApiToken(request.getApiToken().trim());
        }
        if (request.getDefaultFrames() != null) {
            media.setDefaultFrames(request.getDefaultFrames());
        }
        if (request.getDefaultSteps() != null) {
            media.setDefaultSteps(request.getDefaultSteps());
        }
        if (request.getDefaultWidth() != null) {
            media.setDefaultWidth(request.getDefaultWidth());
        }
        if (request.getDefaultHeight() != null) {
            media.setDefaultHeight(request.getDefaultHeight());
        }
        if (request.getImageDefaultWidth() != null) {
            media.setImageDefaultWidth(request.getImageDefaultWidth());
        }
        if (request.getImageDefaultHeight() != null) {
            media.setImageDefaultHeight(request.getImageDefaultHeight());
        }
        if (request.getImageDefaultSteps() != null) {
            media.setImageDefaultSteps(request.getImageDefaultSteps());
        }
        if (request.getArchiveBaseUrl() != null) {
            media.setArchiveBaseUrl(request.getArchiveBaseUrl().trim());
        }
        if (request.getPublicAssetBaseUrl() != null) {
            media.setPublicAssetBaseUrl(request.getPublicAssetBaseUrl().trim());
        }
        return mediaConfigView(media);
    }

    public Map<String, Object> mediaHealth() {
        requireAdmin();
        StoryflowProperties.Media media = properties.getMedia();
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("image", probeOllamaImage());
        var videoHealth = new LinkedHashMap<String, Object>();
        videoHealth.put("baseUrl", "");
        videoHealth.put("online", false);
        videoHealth.put("status", "disabled");
        videoHealth.put("model", "视频生成已下线");
        health.put("video", videoHealth);
        health.put("videoProvider", "disabled");
        health.put("tokenConfigured", media.getApiToken() != null && !media.getApiToken().isBlank());
        return health;
    }

    private Map<String, Object> probeOllamaImage() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", "image");
        String model = ollamaClient.getImageModelName();
        List<String> endpoints = ollamaClient.resolveImageEndpointUrls();
        String base = endpoints.isEmpty() ? "" : endpoints.get(0);
        item.put("baseUrl", base);
        item.put("model", model);
        boolean modelOk = ollamaClient.imageModelAvailable();
        item.put("online", modelOk);
        item.put("modelAvailable", modelOk);
        if (base == null || base.isBlank()) {
            item.put("status", "missing-url");
            item.put("httpStatus", null);
        } else if (!modelOk) {
            item.put("status", "unreachable");
            item.put("httpStatus", null);
        } else {
            item.put("status", "ok");
            item.put("httpStatus", 200);
        }
        return item;
    }

    private Map<String, Object> mediaConfigView(StoryflowProperties.Media media) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("imageBaseUrl", "");
        data.put("imageModel", ollamaClient.getImageModelName());
        data.put("ollamaImageBaseUrl", properties.getOllama().getImageBaseUrl());
        data.put("h3BaseUrl", media.resolveH3BaseUrl());
        data.put("mlxBaseUrl", "");
        data.put("videoProvider", "disabled");
        data.put("geeknowVideoMode", "");
        data.put("geeknowVideoEnabled", false);
        data.put("geeknowBaseUrl", "");
        data.put("geeknowApiKeyMasked", "");
        data.put("geeknowKeyConfigured", false);
        data.put("apiTokenMasked", maskToken(media.getApiToken()));
        data.put("tokenConfigured", media.getApiToken() != null && !media.getApiToken().isBlank());
        data.put("storageDir", media.getStorageDir());
        data.put("archiveBaseUrl", media.getArchiveBaseUrl());
        data.put("publicAssetBaseUrl", media.getPublicAssetBaseUrl());
        data.put("defaultFrames", media.getDefaultFrames());
        data.put("defaultSteps", media.getDefaultSteps());
        data.put("defaultWidth", media.getDefaultWidth());
        data.put("defaultHeight", media.getDefaultHeight());
        data.put("imageDefaultWidth", media.getImageDefaultWidth());
        data.put("imageDefaultHeight", media.getImageDefaultHeight());
        data.put("imageDefaultSteps", media.getImageDefaultSteps());
        data.put("runtimeOnly", true);
        return data;
    }

    private Map<String, Object> probeMediaEndpoint(String baseUrl, String token, String kind) {
        return probeMediaEndpoint(baseUrl, token, kind, "/");
    }

    private Map<String, Object> probeMediaEndpoint(String baseUrl, String token, String kind, String path) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", kind);
        item.put("baseUrl", baseUrl);
        if (baseUrl == null || baseUrl.isBlank()) {
            item.put("online", false);
            item.put("status", "missing-url");
            item.put("httpStatus", 0);
            return item;
        }
        String probeUri = baseUrl + (path == null || path.isBlank() ? "/" : path);
        try {
            Integer status = probeClient.get()
                    .uri(probeUri)
                    .headers(headers -> {
                        if (token != null && !token.isBlank()) {
                            headers.setBearerAuth(token);
                        }
                    })
                    .exchange((request, response) -> response.getStatusCode().value());
            int code = status != null ? status : 0;
            // 401/403 说明服务可达但鉴权失败；2xx/404 也视为在线
            boolean online = code > 0 && code < 500;
            item.put("online", online);
            item.put("httpStatus", code);
            if (code == 401 || code == 403) {
                item.put("status", "auth-required");
            } else if (online) {
                item.put("status", "ok");
            } else {
                item.put("status", "error");
            }
        } catch (Exception ex) {
            item.put("online", false);
            item.put("status", "unreachable");
            item.put("httpStatus", 0);
            item.put("message", ex.getMessage() != null ? ex.getMessage() : "unreachable");
        }
        return item;
    }

    private static String normalizeBase(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        String t = token.trim();
        if (t.length() <= 8) {
            return "****";
        }
        return t.substring(0, 4) + "…" + t.substring(t.length() - 4);
    }

    private static boolean isMaskedToken(String token) {
        String t = token.trim();
        return t.contains("…") || t.contains("...") || t.chars().allMatch(c -> c == '*');
    }

    private AdminUserDtos.UserSummary toUserSummary(User user) {
        AdminUserDtos.UserSummary summary = new AdminUserDtos.UserSummary();
        summary.setId(user.getId());
        summary.setEmail(user.getEmail());
        summary.setNickname(user.getNickname());
        summary.setRole(user.getRole());
        summary.setEnabled(user.isEnabled());
        summary.setCreatedAt(user.getCreatedAt());
        summary.setProjectCount(projectRepository.countByUserId(user.getId()));
        summary.setAiCallCount(aiUsageLogRepository.countByUserId(user.getId()));
        summary.setSystemAccount(isSystemAccount(user));
        return summary;
    }

    private boolean isSystemAccount(User user) {
        if (user == null || user.getEmail() == null) {
            return false;
        }
        String email = user.getEmail().trim().toLowerCase(Locale.ROOT);
        StoryflowProperties.Auth auth = properties.getAuth();
        return emailEquals(email, auth.getGuestEmail())
                || emailEquals(email, "aip-platform@storyflow.local")
                || emailEquals(email, auth.getAdminEmail());
    }

    private boolean emailEquals(String left, String right) {
        return right != null && !right.isBlank()
                && left.equals(right.trim().toLowerCase(Locale.ROOT));
    }

    private UserRole parseRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        try {
            return UserRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("无效的角色筛选");
        }
    }

    @Data
    public static class AiConfigRequest {
        private String provider = "ollama";
        private String baseUrl;
        private String modelName;
        private String apiKey;
        private double temperature = 0.3;
        private int maxTokens = 4096;
    }

    @Data
    public static class MediaConfigRequest {
        /** @deprecated 生图已改 Ollama，忽略 */
        private String imageBaseUrl;
        private String imageModel;
        private String ollamaImageBaseUrl;
        private String h3BaseUrl;
        private String mlxBaseUrl;
        private String videoProvider;
        private String geeknowApiKey;
        private String apiToken;
        private Integer defaultFrames;
        private Integer defaultSteps;
        private Integer defaultWidth;
        private Integer defaultHeight;
        private Integer imageDefaultWidth;
        private Integer imageDefaultHeight;
        private Integer imageDefaultSteps;
        private String archiveBaseUrl;
        private String publicAssetBaseUrl;
    }
}
