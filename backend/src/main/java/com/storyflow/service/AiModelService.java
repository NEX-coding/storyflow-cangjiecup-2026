package com.storyflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyflow.ai.CodexClient;
import com.storyflow.ai.OllamaClient;
import com.storyflow.config.StoryflowProperties;
import com.storyflow.entity.AiModelConfig;
import com.storyflow.repository.AiModelConfigRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class AiModelService {

    private final AiModelConfigRepository aiModelConfigRepository;
    private final OllamaClient ollamaClient;
    private final CodexClient codexClient;
    private final StoryflowProperties properties;
    private final ObjectMapper objectMapper;

    public AiModelService(AiModelConfigRepository aiModelConfigRepository,
                          OllamaClient ollamaClient,
                          CodexClient codexClient,
                          StoryflowProperties properties,
                          ObjectMapper objectMapper) {
        this.aiModelConfigRepository = aiModelConfigRepository;
        this.ollamaClient = ollamaClient;
        this.codexClient = codexClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 所有人可读：当前对接的 AI 服务与端点状态（不含 API Key） */
    public Map<String, Object> publicStatus() {
        Map<String, Object> health = new HashMap<>();
        // Probe each endpoint once via endpointsStatus (short timeouts). Avoid
        // also calling healthCheck/modelAvailable — those re-probe every URL and
        // previously hung Tomcat+H2 when an upstream FRP tunnel stalled.
        var endpoints = ollamaClient.endpointsStatus();
        boolean anyOnline = endpoints.stream().anyMatch(e -> Boolean.TRUE.equals(e.get("online")));
        boolean anyModel = endpoints.stream().anyMatch(e -> Boolean.TRUE.equals(e.get("modelAvailable")));
        health.put("online", anyOnline);
        health.put("modelAvailable", anyModel);
        health.put("activeBaseUrl", ollamaClient.getBaseUrl());
        health.put("activeModel", ollamaClient.getModelName());
        health.put("endpoints", endpoints);

        var override = com.storyflow.ai.AiRequestOverride.get();
        if (override != null && override.isUsable()) {
            health.put("personalOverride", true);
            health.put("provider", override.provider() != null ? override.provider() : "openai");
            health.put("baseUrl", override.baseUrl());
            health.put("model", override.modelName());
            if (override.temperature() != null) {
                health.put("temperature", override.temperature());
            }
            if (override.maxTokens() != null) {
                health.put("maxTokens", override.maxTokens());
            }
            return health;
        }

        aiModelConfigRepository.findFirstByActiveTrueOrderByIdDesc().ifPresentOrElse(config -> {
            health.put("provider", config.getProvider());
            health.put("baseUrl", config.getBaseUrl());
            health.put("model", config.getModelName());
            putSafeConfigJson(health, config.getConfigJson());
        }, () -> {
            health.put("baseUrl", ollamaClient.getBaseUrl());
            health.put("model", ollamaClient.getPrimaryModelName());
        });
        health.put("personalOverride", false);
        if (properties.getAssistant().isExposeStatus()) {
            health.put("assistant", codexClient.statusSnapshot());
        }
        return health;
    }

    private void putSafeConfigJson(Map<String, Object> target, String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(configJson);
            if (node.has("temperature")) {
                target.put("temperature", node.get("temperature").asDouble());
            }
            if (node.has("maxTokens")) {
                target.put("maxTokens", node.get("maxTokens").asInt());
            }
        } catch (Exception ignored) {
        }
    }
}
