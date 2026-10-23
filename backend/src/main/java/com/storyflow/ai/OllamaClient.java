package com.storyflow.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyflow.common.BusinessException;
import com.storyflow.config.StoryflowProperties;
import com.storyflow.entity.AiModelConfig;
import com.storyflow.repository.AiModelConfigRepository;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class OllamaClient {

    private final StoryflowProperties properties;
    private final AiModelConfigRepository configRepository;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    /** Short-timeout client for health/status probes — never use the 10min chat client. */
    private final RestClient probeRestClient;
    private final HttpClient httpClient;

    /** 当前生效端点（失败后自动切到备用） */
    private volatile String activeBaseUrl;

    public OllamaClient(StoryflowProperties properties, AiModelConfigRepository configRepository, ObjectMapper objectMapper) {
        this.properties = properties;
        this.configRepository = configRepository;
        this.objectMapper = objectMapper;

        // 连接超时宜短：死端点尽快 failover，避免闲聊卡住
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMinutes(10));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();

        HttpClient probeHttp = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory probeFactory = new JdkClientHttpRequestFactory(probeHttp);
        probeFactory.setReadTimeout(Duration.ofSeconds(3));
        this.probeRestClient = RestClient.builder().requestFactory(probeFactory).build();
    }

    /**
     * Ollama 文生图 / 图生图（FLUX.2 Klein 等 image 模型）。
     * 协议：POST /api/generate → 响应字段 {@code image}（base64 PNG）。
     *
     * @param referenceImagesB64 可选参考图（已是纯 base64，可多张）
     */
    public byte[] generateImage(String prompt, Integer width, Integer height, List<String> referenceImagesB64) {
        return generateImage(prompt, width, height, null, referenceImagesB64);
    }

    public byte[] generateImage(
            String prompt, Integer width, Integer height, Integer steps, List<String> referenceImagesB64) {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException("生图提示词不能为空");
        }
        return executeWithFailoverBytes(baseUrl ->
                generateImageAt(baseUrl, prompt.trim(), width, height, steps, referenceImagesB64));
    }

    public String getImageModelName() {
        String configured = properties.getOllama().getImageModel();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return "x/flux2-klein:4b-fp8";
    }

    public boolean imageModelAvailable() {
        String imageModel = getImageModelName();
        for (String baseUrl : resolveImageEndpointUrls()) {
            if (probeNamedModelAt(baseUrl, imageModel)) {
                return true;
            }
        }
        return false;
    }

    /** 生图端点（优先 image-base-url；无效则回退本机 11434，再回退文本端点） */
    public List<String> resolveImageEndpointUrls() {
        List<String> urls = new ArrayList<>();
        String dedicated = properties.getOllama().getImageBaseUrl();
        if (dedicated != null && !dedicated.isBlank() && isAbsoluteHttpUrl(dedicated.trim())) {
            addUnique(urls, dedicated.trim());
            return urls;
        }
        // 避免空串 / 相对路径导致 RestClient「URI is not absolute」
        addUnique(urls, "http://127.0.0.1:11434");
        for (String fallback : resolveEndpointUrls()) {
            if (isAbsoluteHttpUrl(fallback)) {
                addUnique(urls, fallback);
            }
        }
        return urls;
    }

    private static boolean isAbsoluteHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String u = url.trim().toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }

    public String chatJson(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, true, null);
    }

    public String chatJson(String systemPrompt, String userPrompt, int maxTokens) {
        return chat(systemPrompt, userPrompt, true, maxTokens);
    }

    public String chatText(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, false, null);
    }

    public String chatText(String systemPrompt, String userPrompt, int maxTokens) {
        return chat(systemPrompt, userPrompt, false, maxTokens);
    }

    public String chat(String systemPrompt, String userPrompt) {
        return chatJson(systemPrompt, userPrompt);
    }

    public String streamChatText(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        return streamChatText(systemPrompt, userPrompt, null, onToken);
    }

    public String streamChatText(String systemPrompt, String userPrompt, Integer maxTokens, Consumer<String> onToken) {
        int predict = resolveMaxTokens(maxTokens);
        return executeWithFailover(baseUrl -> streamChatAt(baseUrl, systemPrompt, userPrompt, predict, onToken));
    }

    private String chat(String systemPrompt, String userPrompt, boolean jsonFormat, Integer maxTokens) {
        int predict = resolveMaxTokens(maxTokens);
        return executeWithFailover(baseUrl -> chatAt(baseUrl, systemPrompt, userPrompt, jsonFormat, predict));
    }

    private interface EndpointCall {
        String run(String baseUrl) throws Exception;
    }

    private String executeWithFailover(EndpointCall call) {
        List<String> endpoints = resolveEndpointUrls();
        Exception last = null;
        int start = indexOfActive(endpoints);
        for (int i = 0; i < endpoints.size(); i++) {
            String baseUrl = endpoints.get((start + i) % endpoints.size());
            try {
                String result = call.run(baseUrl);
                activeBaseUrl = baseUrl;
                return result;
            } catch (BusinessException ex) {
                last = ex;
                if (baseUrl.equals(activeBaseUrl)) {
                    activeBaseUrl = null;
                }
            } catch (Exception ex) {
                last = ex;
                if (baseUrl.equals(activeBaseUrl)) {
                    activeBaseUrl = null;
                }
            }
        }
        String msg = last != null ? last.getMessage() : "所有模型端点不可用";
        throw new BusinessException("无法连接模型服务（已尝试 " + endpoints.size() + " 个端点）: " + msg);
    }

    private interface EndpointBytesCall {
        byte[] run(String baseUrl) throws Exception;
    }

    private byte[] executeWithFailoverBytes(EndpointBytesCall call) {
        List<String> endpoints = resolveImageEndpointUrls();
        Exception last = null;
        for (String baseUrl : endpoints) {
            try {
                return call.run(baseUrl);
            } catch (BusinessException ex) {
                last = ex;
            } catch (Exception ex) {
                last = ex;
            }
        }
        String msg = last != null ? last.getMessage() : "所有生图端点不可用";
        throw new BusinessException("无法连接生图服务（已尝试 " + endpoints.size() + " 个端点）: " + msg);
    }

    private byte[] generateImageAt(
            String baseUrl,
            String prompt,
            Integer width,
            Integer height,
            Integer steps,
            List<String> referenceImagesB64) throws Exception {
        String transport = transportUrl(baseUrl);
        if (isOpenAiCompatible(transport)) {
            transport = stripOpenAiSuffix(transport);
        }
        if (!isAbsoluteHttpUrl(transport)) {
            throw new BusinessException(
                    "生图 Ollama 地址无效（需绝对 URL，如 http://127.0.0.1:11434），当前: "
                            + (transport == null || transport.isBlank() ? "(空)" : transport));
        }
        String generateUrl = transport.endsWith("/")
                ? transport + "api/generate"
                : transport + "/api/generate";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", getImageModelName());
        body.put("prompt", prompt);
        body.put("stream", false);
        if (width != null && width > 0) {
            body.put("width", width);
        }
        if (height != null && height > 0) {
            body.put("height", height);
        }
        // FLUX.2 Klein：不传 steps 时本机 FP4 常见蓝噪花屏；默认至少 8 步
        int stepCount = steps != null && steps > 0 ? steps : 8;
        body.put("steps", stepCount);
        if (referenceImagesB64 != null && !referenceImagesB64.isEmpty()) {
            List<String> images = new ArrayList<>();
            for (String raw : referenceImagesB64) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                images.add(stripDataUrl(raw.trim()));
                if (images.size() >= 4) {
                    break;
                }
            }
            if (!images.isEmpty()) {
                body.put("images", images);
            }
        }

        var req = restClient.post()
                .uri(java.net.URI.create(generateUrl))
                .contentType(MediaType.APPLICATION_JSON);
        applyAuth(req);
        String response = req.body(body).retrieve().body(String.class);
        if (response == null || response.isBlank()) {
            throw new BusinessException("Ollama 生图返回空响应");
        }
        JsonNode root = objectMapper.readTree(response);
        String imageB64 = root.path("image").asText("");
        if (imageB64.isBlank()) {
            String err = root.path("error").asText("");
            throw new BusinessException(err.isBlank() ? "Ollama 生图未返回 image 字段" : ("Ollama 生图失败: " + err));
        }
        try {
            return java.util.Base64.getDecoder().decode(imageB64);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Ollama 生图 base64 无效");
        }
    }

    private static String stripOpenAiSuffix(String url) {
        String u = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        if (u.endsWith("/v1")) {
            return u.substring(0, u.length() - 3);
        }
        return u;
    }

    private static String stripDataUrl(String b64) {
        String s = b64.trim();
        int comma = s.indexOf(',');
        if (comma > 0 && s.substring(0, comma).toLowerCase().contains("base64")) {
            return s.substring(comma + 1);
        }
        return s;
    }

    private int indexOfActive(List<String> endpoints) {
        if (activeBaseUrl == null) {
            return 0;
        }
        int idx = endpoints.indexOf(activeBaseUrl);
        return idx >= 0 ? idx : 0;
    }

    private String chatAt(String baseUrl, String systemPrompt, String userPrompt, boolean jsonFormat, int predict)
            throws Exception {
        String transport = transportUrl(baseUrl);
        if (isOpenAiCompatible(transport)) {
            return openAiChat(transport, systemPrompt, userPrompt, jsonFormat, predict, false, null);
        }
        return ollamaNativeChat(transport, systemPrompt, userPrompt, jsonFormat, predict);
    }

    private String streamChatAt(String baseUrl, String systemPrompt, String userPrompt, int predict,
                                Consumer<String> onToken) throws Exception {
        String transport = transportUrl(baseUrl);
        if (isOpenAiCompatible(transport)) {
            return openAiChat(transport, systemPrompt, userPrompt, false, predict, true, onToken);
        }
        return ollamaNativeStreamChat(transport, systemPrompt, userPrompt, predict, onToken);
    }

    /**
     * 将配置的模型服务地址映射到本地转发端口。
     * 注意：必须走 Ollama 原生 {@code /api/chat}（不要 /v1），
     * 否则 qwen3.5 的 OpenAI 兼容层会把输出塞进 reasoning、正文为空且极慢。
     */
    private String transportUrl(String configuredBaseUrl) {
        String u = normalizeUrl(configuredBaseUrl);
        if (properties.getOllama().isLocalModelApiRemap()) {
            if (u.contains("model-api-mac")) {
                return "http://127.0.0.1:11436";
            }
            if (u.contains("model-api") && !u.contains("model-api-mac")) {
                return "http://127.0.0.1:11435";
            }
        }
        // 已是本地落地地址时，剥掉误配的 /v1，强制原生协议
        if (u.startsWith("http://127.0.0.1:11436") || u.startsWith("http://localhost:11436")) {
            return "http://127.0.0.1:11436";
        }
        if (u.startsWith("http://127.0.0.1:11435") || u.startsWith("http://localhost:11435")) {
            return "http://127.0.0.1:11435";
        }
        return u;
    }

    private String ollamaNativeChat(String baseUrl, String systemPrompt, String userPrompt, boolean jsonFormat,
                                  int predict) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", getModelNameForEndpoint(baseUrl));
        body.put("stream", false);
        body.put("think", false);
        if (jsonFormat) {
            body.put("format", "json");
        }
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        body.put("options", buildOllamaOptions(predict));

        var req = restClient.post()
                .uri(baseUrl + "/api/chat")
                .contentType(MediaType.APPLICATION_JSON);
        applyAuth(req);
        String response = req.body(body).retrieve().body(String.class);

        JsonNode root = objectMapper.readTree(response);
        JsonNode content = root.path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new BusinessException("Ollama 返回空内容");
        }
        return content.asText();
    }

    private String ollamaNativeStreamChat(String baseUrl, String systemPrompt, String userPrompt, int predict,
                                          Consumer<String> onToken) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", getModelNameForEndpoint(baseUrl));
        body.put("stream", true);
        body.put("think", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        body.put("options", buildOllamaOptions(predict));

        String payload = objectMapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        applyAuth(builder);
        HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            throw new BusinessException("Ollama 流式请求失败，HTTP " + response.statusCode());
        }

        StringBuilder full = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode root = objectMapper.readTree(line);
                String delta = root.path("message").path("content").asText("");
                if (!delta.isEmpty()) {
                    full.append(delta);
                    if (onToken != null) {
                        onToken.accept(delta);
                    }
                }
                if (root.path("done").asBoolean(false)) {
                    break;
                }
            }
        }
        if (full.isEmpty()) {
            throw new BusinessException("Ollama 流式返回空内容");
        }
        return full.toString();
    }

    private String openAiChat(String baseUrl, String systemPrompt, String userPrompt, boolean jsonFormat,
                              int predict, boolean stream, Consumer<String> onToken) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", getModelNameForEndpoint(baseUrl));
        body.put("stream", stream);
        body.put("temperature", resolveTemperature());
        body.put("max_tokens", predict);
        // qwen3.5 思考链：尽量关闭（原生接口更可靠；此处作兼容兜底）
        body.put("think", false);
        body.put("enable_thinking", false);
        body.put("chat_template_kwargs", Map.of("enable_thinking", false));
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        if (jsonFormat) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        String payload = objectMapper.writeValueAsString(body);
        String chatUrl = baseUrl + "/chat/completions";
        if (stream) {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(chatUrl))
                    .timeout(Duration.ofMinutes(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            applyAuth(builder);
            HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                throw new BusinessException("OpenAI 兼容流式请求失败，HTTP " + response.statusCode());
            }
            StringBuilder full = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) {
                        continue;
                    }
                    JsonNode root = objectMapper.readTree(data);
                    String delta = root.path("choices").path(0).path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        full.append(delta);
                        if (onToken != null) {
                            onToken.accept(delta);
                        }
                    }
                }
            }
            if (full.isEmpty()) {
                throw new BusinessException("OpenAI 兼容流式返回空内容");
            }
            return full.toString();
        }

        var req = restClient.post().uri(chatUrl).contentType(MediaType.APPLICATION_JSON);
        applyAuth(req);
        String response = req.body(body).retrieve().body(String.class);
        JsonNode root = objectMapper.readTree(response);
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new BusinessException("OpenAI 兼容接口返回空内容");
        }
        return content;
    }

    private Map<String, Object> buildOllamaOptions(int predict) {
        Map<String, Object> options = new HashMap<>();
        options.put("temperature", resolveTemperature());
        options.put("num_predict", predict);
        options.put("num_ctx", properties.getOllama().getNumCtx());
        return options;
    }

    private int resolveMaxTokens(Integer maxTokens) {
        if (maxTokens != null) {
            return maxTokens;
        }
        AiRequestOverride.Override override = AiRequestOverride.get();
        if (override != null && override.maxTokens() != null && override.maxTokens() > 0) {
            return override.maxTokens();
        }
        return properties.getOllama().getMaxTokens();
    }

    private double resolveTemperature() {
        AiRequestOverride.Override override = AiRequestOverride.get();
        if (override != null && override.temperature() != null) {
            return override.temperature();
        }
        return properties.getOllama().getTemperature();
    }

    public boolean healthCheck() {
        for (String baseUrl : resolveEndpointUrls()) {
            if (probeEndpoint(baseUrl)) {
                return true;
            }
        }
        return probeStatusUrl();
    }

    public boolean modelAvailable() {
        for (String baseUrl : resolveEndpointUrls()) {
            if (probeModelAt(baseUrl)) {
                return true;
            }
        }
        return false;
    }

    public String getBaseUrl() {
        AiRequestOverride.Override override = AiRequestOverride.get();
        if (override != null && override.isUsable()) {
            return normalizeUrl(override.baseUrl());
        }
        if (activeBaseUrl != null) {
            return activeBaseUrl;
        }
        List<String> urls = resolveEndpointUrls();
        return urls.isEmpty() ? properties.getOllama().getBaseUrl() : urls.get(0);
    }

    public String getPrimaryModelName() {
        AiRequestOverride.Override override = AiRequestOverride.get();
        if (override != null && override.isUsable()) {
            return override.modelName();
        }
        return configRepository.findFirstByActiveTrueOrderByIdDesc()
                .map(AiModelConfig::getModelName)
                .orElse(properties.getOllama().getModel());
    }

    /** 当前请求使用的模型（failover 到备用端点时用 fallback-model） */
    public String getModelName() {
        AiRequestOverride.Override override = AiRequestOverride.get();
        if (override != null && override.isUsable()) {
            return override.modelName();
        }
        if (activeBaseUrl != null) {
            return getModelNameForEndpoint(activeBaseUrl);
        }
        return getPrimaryModelName();
    }

    private String getModelNameForEndpoint(String baseUrl) {
        AiRequestOverride.Override override = AiRequestOverride.get();
        if (override != null && override.isUsable()) {
            return override.modelName();
        }
        List<String> endpoints = resolveEndpointUrls();
        String normalized = normalizeUrl(baseUrl);
        int idx = endpoints.indexOf(normalized);
        if (idx <= 0) {
            return getPrimaryModelName();
        }
        String u = normalized.toLowerCase();
        if (u.contains("model-api") && !u.contains("model-api-mac")) {
            String secondary = properties.getOllama().getSecondaryFallbackModel();
            if (secondary != null && !secondary.isBlank()) {
                return secondary;
            }
            return "qwen3.5:0.8b";
        }
        String fallbackModel = properties.getOllama().getFallbackModel();
        if (fallbackModel != null && !fallbackModel.isBlank()) {
            return fallbackModel;
        }
        return getPrimaryModelName();
    }

    public String getApiKey() {
        AiRequestOverride.Override override = AiRequestOverride.get();
        if (override != null && override.apiKey() != null && !override.apiKey().isBlank()) {
            return override.apiKey();
        }
        return configRepository.findFirstByActiveTrueOrderByIdDesc()
                .map(AiModelConfig::getConfigJson)
                .map(this::readApiKey)
                .filter(k -> k != null && !k.isBlank())
                .orElseGet(() -> {
                    String env = System.getenv("OLLAMA_API_KEY");
                    return env != null && !env.isBlank() ? env : null;
                });
    }

    /** 管理端：各端点在线情况 */
    public List<Map<String, Object>> endpointsStatus() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String baseUrl : resolveEndpointUrls()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("baseUrl", baseUrl);
            item.put("model", getModelNameForEndpoint(baseUrl));
            item.put("apiMode", isOpenAiCompatible(baseUrl) ? "openai" : "ollama");
            item.put("online", probeEndpoint(baseUrl));
            item.put("modelAvailable", probeModelAt(baseUrl));
            item.put("active", baseUrl.equals(getBaseUrl()));
            out.add(item);
        }
        String statusUrl = properties.getOllama().getStatusUrl();
        if (statusUrl != null && !statusUrl.isBlank()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("baseUrl", statusUrl.trim());
            item.put("apiMode", "status-page");
            item.put("online", probeStatusUrl());
            item.put("modelAvailable", false);
            item.put("active", false);
            out.add(item);
        }
        return out;
    }

    private List<String> resolveEndpointUrls() {
        List<String> urls = new ArrayList<>();
        AiRequestOverride.Override override = AiRequestOverride.get();
        if (override != null && override.isUsable()) {
            addUnique(urls, override.baseUrl());
            return urls;
        }
        String primary = configRepository.findFirstByActiveTrueOrderByIdDesc()
                .map(AiModelConfig::getBaseUrl)
                .filter(u -> u != null && !u.isBlank())
                .orElse(properties.getOllama().getBaseUrl());
        addUnique(urls, primary);
        for (String fallback : properties.getOllama().getFallbackUrls()) {
            addUnique(urls, fallback);
        }
        if (urls.isEmpty()) {
            urls.add(normalizeUrl(properties.getOllama().getBaseUrl()));
        }
        return urls;
    }

    private void addUnique(List<String> urls, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String normalized = normalizeUrl(raw);
        if (!urls.contains(normalized)) {
            urls.add(normalized);
        }
    }

    private String normalizeUrl(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private boolean isOpenAiCompatible(String baseUrl) {
        return baseUrl.endsWith("/v1");
    }

    private boolean probeEndpoint(String baseUrl) {
        try {
            String transport = transportUrl(baseUrl);
            if (isOpenAiCompatible(transport)) {
                var req = probeRestClient.get().uri(transport + "/models");
                applyAuth(req);
                req.retrieve().body(String.class);
                return true;
            }
            probeTagsRequest(transport).retrieve().body(String.class);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean probeModelAt(String baseUrl) {
        return probeNamedModelAt(baseUrl, getModelNameForEndpoint(baseUrl));
    }

    private boolean probeNamedModelAt(String baseUrl, String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        try {
            String transport = transportUrl(baseUrl);
            if (isOpenAiCompatible(transport)) {
                transport = stripOpenAiSuffix(transport);
            }
            String response = probeTagsRequest(transport).retrieve().body(String.class);
            if (response == null) {
                return false;
            }
            if (response.contains(model)) {
                return true;
            }
            String bare = model.contains(":") ? model.substring(0, model.indexOf(':')) : model;
            return response.contains(bare);
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean probeStatusUrl() {
        String statusUrl = properties.getOllama().getStatusUrl();
        if (statusUrl == null || statusUrl.isBlank()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl.trim()))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 400;
        } catch (Exception ex) {
            return false;
        }
    }

    private RestClient.RequestHeadersSpec<?> tagsRequest(String baseUrl) {
        RestClient.RequestHeadersSpec<?> req = restClient.get().uri(baseUrl + "/api/tags");
        applyAuth(req);
        return req;
    }

    private RestClient.RequestHeadersSpec<?> probeTagsRequest(String baseUrl) {
        RestClient.RequestHeadersSpec<?> req = probeRestClient.get().uri(baseUrl + "/api/tags");
        applyAuth(req);
        return req;
    }

    private void applyAuth(RestClient.RequestHeadersSpec<?> req) {
        String apiKey = getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            req.header("Authorization", "Bearer " + apiKey);
        }
    }

    private void applyAuth(HttpRequest.Builder builder) {
        String apiKey = getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
    }

    private String readApiKey(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(configJson);
            String key = node.path("apiKey").asText(null);
            return key != null && !key.isBlank() ? key : null;
        } catch (Exception ex) {
            return null;
        }
    }
}
