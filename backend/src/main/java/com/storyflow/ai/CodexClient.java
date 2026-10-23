package com.storyflow.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyflow.config.StoryflowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 本机 Cursor Agent（Codex）封装：以 ask 模式做只读智能问答。
 * 创作写操作仍由 {@link com.storyflow.assistant.AssistantService} 意图工具链执行。
 */
@Component
public class CodexClient {

    private static final Logger log = LoggerFactory.getLogger(CodexClient.class);
    private static final long AVAILABILITY_CACHE_MS = 45_000L;

    private final StoryflowProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean cachedAvailable = new AtomicBoolean(false);
    private final AtomicLong cachedAt = new AtomicLong(0);

    public CodexClient(StoryflowProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        String provider = provider();
        return "hybrid".equals(provider) || "codex".equals(provider);
    }

    public boolean isAvailable() {
        if (!isEnabled()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - cachedAt.get() < AVAILABILITY_CACHE_MS) {
            return cachedAvailable.get();
        }
        boolean ok = probeAvailable();
        cachedAvailable.set(ok);
        cachedAt.set(now);
        return ok;
    }

    public Map<String, Object> statusSnapshot() {
        String bin = resolveBin();
        boolean enabled = isEnabled();
        boolean available = enabled && isAvailable();
        return Map.of(
                "provider", provider(),
                "enabled", enabled,
                "available", available,
                "bin", bin == null ? "" : bin,
                "model", nullToEmpty(properties.getAssistant().getCodexModel()),
                "mode", "ask"
        );
    }

    public String chatAsk(String systemPrompt, String userPrompt) {
        return streamAsk(systemPrompt, userPrompt, ignored -> {});
    }

    public String streamAsk(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        String bin = resolveBin();
        if (bin == null) {
            throw new IllegalStateException("未找到 cursor-agent / agent，可设置 storyflow.assistant.codex-bin");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(bin);
        cmd.add("--mode");
        cmd.add("ask");
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--stream-partial-output");
        cmd.add("--trust");
        cmd.add("--sandbox");
        cmd.add("enabled");

        String model = properties.getAssistant().getCodexModel();
        if (model != null && !model.isBlank()) {
            cmd.add("--model");
            cmd.add(model.trim());
        }

        String workspace = properties.getAssistant().getCodexWorkspace();
        if (workspace != null && !workspace.isBlank()) {
            cmd.add("--workspace");
            cmd.add(workspace.trim());
        }

        String prompt = buildPrompt(systemPrompt, userPrompt);
        cmd.add(prompt);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        String apiKey = firstNonBlank(System.getenv("CURSOR_API_KEY"), System.getenv("CURSOR_AUTH_TOKEN"));
        if (apiKey != null) {
            pb.environment().put("CURSOR_API_KEY", apiKey);
        }

        Process process;
        try {
            process = pb.start();
        } catch (Exception ex) {
            invalidateCache();
            throw new IllegalStateException("启动 Codex 失败：" + ex.getMessage(), ex);
        }

        StringBuilder full = new StringBuilder();
        StringBuilder emitted = new StringBuilder();
        long timeoutMs = Math.max(10_000L, properties.getAssistant().getCodexTimeoutMs());
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String delta = extractTextDelta(line, emitted);
                if (delta != null && !delta.isEmpty()) {
                    full.append(delta);
                    if (onToken != null) {
                        onToken.accept(delta);
                    }
                }
            }
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                invalidateCache();
                throw new IllegalStateException("Codex 响应超时（" + timeoutMs + "ms）");
            }
            int code = process.exitValue();
            if (code != 0 && full.isEmpty()) {
                invalidateCache();
                throw new IllegalStateException("Codex 退出码 " + code + "（可能未登录，请执行 cursor-agent login 或设置 CURSOR_API_KEY）");
            }
            return full.toString().trim();
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            process.destroyForcibly();
            invalidateCache();
            throw new IllegalStateException("Codex 调用失败：" + ex.getMessage(), ex);
        }
    }

    private boolean probeAvailable() {
        String bin = resolveBin();
        if (bin == null) {
            return false;
        }
        if (firstNonBlank(System.getenv("CURSOR_API_KEY"), System.getenv("CURSOR_AUTH_TOKEN")) != null) {
            return true;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(bin, "status");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            String out;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                out = sb.toString().toLowerCase(Locale.ROOT);
            }
            if (process.exitValue() != 0) {
                return false;
            }
            return !out.contains("not logged in") && !out.contains("authentication required");
        } catch (Exception ex) {
            log.debug("Codex availability probe failed: {}", ex.toString());
            return false;
        }
    }

    private String resolveBin() {
        String configured = properties.getAssistant().getCodexBin();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.trim());
            if (Files.isExecutable(p)) {
                return p.toAbsolutePath().toString();
            }
            return configured.trim();
        }
        String home = System.getProperty("user.home", "");
        List<String> candidates = List.of(
                home + "/.local/bin/cursor-agent",
                home + "/.local/bin/agent",
                "/opt/homebrew/bin/cursor-agent",
                "/usr/local/bin/cursor-agent",
                "cursor-agent",
                "agent"
        );
        for (String c : candidates) {
            if (c.contains("/")) {
                Path p = Path.of(c);
                if (Files.isExecutable(p)) {
                    return p.toAbsolutePath().toString();
                }
            } else if (which(c) != null) {
                return c;
            }
        }
        return null;
    }

    private static String which(String name) {
        try {
            Process process = new ProcessBuilder("which", name).start();
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                return line == null || line.isBlank() ? null : line.trim();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractTextDelta(String line, StringBuilder emittedSoFar) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String trimmed = line.trim();
        if (!trimmed.startsWith("{")) {
            // 非 JSON 行：若像错误提示则忽略，避免污染回复
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.contains("authentication required") || lower.contains("not logged in")) {
                throw new IllegalStateException(trimmed);
            }
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            String type = textOrEmpty(node.get("type"));
            if ("result".equals(type) || "system".equals(type) || "tool_call".equals(type)
                    || "tool_result".equals(type) || "user".equals(type)) {
                return null;
            }
            String candidate = firstText(node);
            if (candidate == null || candidate.isEmpty()) {
                return null;
            }
            // 有的事件是累计全文，有的是增量；统一成增量
            if (candidate.startsWith(emittedSoFar.toString())) {
                String delta = candidate.substring(emittedSoFar.length());
                emittedSoFar.setLength(0);
                emittedSoFar.append(candidate);
                return delta;
            }
            emittedSoFar.append(candidate);
            return candidate;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            log.trace("skip codex line: {}", ex.toString());
            return null;
        }
    }

    private static String firstText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.has("text") && node.get("text").isTextual()) {
            return node.get("text").asText();
        }
        if (node.has("delta") && node.get("delta").isTextual()) {
            return node.get("delta").asText();
        }
        JsonNode message = node.get("message");
        if (message != null) {
            JsonNode content = message.get("content");
            if (content != null && content.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : content) {
                    if (block != null && "text".equals(textOrEmpty(block.get("type")))
                            && block.has("text") && block.get("text").isTextual()) {
                        sb.append(block.get("text").asText());
                    }
                }
                if (!sb.isEmpty()) {
                    return sb.toString();
                }
            }
            if (message.has("content") && message.get("content").isTextual()) {
                return message.get("content").asText();
            }
        }
        JsonNode content = node.get("content");
        if (content != null && content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if (block != null && block.has("text") && block.get("text").isTextual()) {
                    sb.append(block.get("text").asText());
                }
            }
            if (!sb.isEmpty()) {
                return sb.toString();
            }
        }
        return null;
    }

    private static String buildPrompt(String systemPrompt, String userPrompt) {
        return """
                %s

                ---
                %s
                """.formatted(
                systemPrompt == null ? "" : systemPrompt.trim(),
                userPrompt == null ? "" : userPrompt.trim()
        ).trim();
    }

    private String provider() {
        String p = properties.getAssistant().getProvider();
        return p == null || p.isBlank() ? "hybrid" : p.trim().toLowerCase(Locale.ROOT);
    }

    private void invalidateCache() {
        cachedAt.set(0);
        cachedAvailable.set(false);
    }

    private static String textOrEmpty(JsonNode node) {
        return node == null || !node.isTextual() ? "" : node.asText();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
