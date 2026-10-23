package com.storyflow.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyflow.ai.OllamaClient;
import com.storyflow.ai.PromptCatalog;
import com.storyflow.common.BusinessException;
import com.storyflow.config.StoryflowProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LlmJsonSupport {

    static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    /** 模型常输出 "type": first_frame（未加引号），Jackson 会报 Unrecognized token */
    static final Pattern UNQUOTED_REF_TYPE = Pattern.compile(
            "(:\\s*)(first_frame|last_frame|other)(\\s*[,}\\]])",
            Pattern.CASE_INSENSITIVE);
    static final Pattern UNQUOTED_TYPE_KEY = Pattern.compile(
            "([{\\[,]\\s*)(type)(\\s*:)",
            Pattern.CASE_INSENSITIVE);

    private final OllamaClient ollamaClient;
    private final PromptCatalog promptCatalog;
    private final ObjectMapper objectMapper;
    private final StoryflowProperties properties;

    public LlmJsonSupport(OllamaClient ollamaClient, PromptCatalog promptCatalog,
                          ObjectMapper objectMapper, StoryflowProperties properties) {
        this.ollamaClient = ollamaClient;
        this.promptCatalog = promptCatalog;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public JsonNode callJsonWithRetry(String system, String user) {
        return callJsonWithRetry(system, user, false);
    }

    /** 资产分类提取：合法空数组视为成功（无该类资产） */
    public JsonNode callJsonAllowEmpty(String system, String user) {
        return callJsonWithRetry(system, user, true);
    }

    public JsonNode callJsonWithRetry(String system, String user, boolean allowEmptyItems) {
        int maxRetries = properties.getOllama().getMaxRetries();
        Exception last = null;
        for (int i = 0; i < maxRetries; i++) {
            try {
                String raw = ollamaClient.chatJson(system, user);
                JsonNode node = parseModelJson(raw);
                JsonNode items = itemsNode(node);
                if (items.isArray() && (allowEmptyItems || !items.isEmpty())) {
                    return node;
                }
                last = new BusinessException("AI 返回空 items");
            } catch (Exception ex) {
                last = ex;
            }
        }
        throw new BusinessException("AI 生成失败，已重试 " + maxRetries + " 次: " +
                (last != null ? last.getMessage() : "未知错误"));
    }

    /** 提取并容错解析模型 JSON（修复未加引号的 first_frame、截断未闭合等） */
    public JsonNode parseModelJson(String raw) throws Exception {
        String json = repairLooseJson(extractJson(raw));
        try {
            return objectMapper.readTree(json);
        } catch (Exception first) {
            String closed = closeTruncatedJson(json);
            if (!closed.equals(json)) {
                try {
                    return objectMapper.readTree(repairLooseJson(closed));
                } catch (Exception ignored) {
                    // fall through
                }
            }
            String retry = repairLooseJson(json);
            if (!retry.equals(json)) {
                return objectMapper.readTree(retry);
            }
            throw first;
        }
    }

    /**
     * 修复模型常见的松散 JSON：
     * - "type": first_frame → "type": "first_frame"
     * - {type: "..." → {"type": "..."
     */
    public String repairLooseJson(String json) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        String fixed = json.trim();
        // 去掉可能残留的思考标签
        fixed = fixed.replaceAll("(?s)<think>.*?</think>", "");
        fixed = UNQUOTED_REF_TYPE.matcher(fixed).replaceAll("$1\"$2\"$3");
        fixed = UNQUOTED_TYPE_KEY.matcher(fixed).replaceAll("$1\"$2\"$3");
        // 尾部多余逗号
        fixed = fixed.replaceAll(",\\s*([}\\]])", "$1");
        return fixed;
    }

    /** 补全因 max_tokens 截断而未闭合的字符串 / 数组 / 对象 */
    public String closeTruncatedJson(String json) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder(json.trim());
        boolean inString = false;
        boolean escape = false;
        Deque<Character> stack = new ArrayDeque<>();
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                    continue;
                }
                if (c == '\\') {
                    escape = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{' || c == '[') {
                stack.push(c);
            } else if (c == '}' || c == ']') {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
            }
        }
        if (inString) {
            sb.append('"');
        }
        // 去掉闭合前的尾逗号
        int end = sb.length() - 1;
        while (end >= 0 && Character.isWhitespace(sb.charAt(end))) {
            end--;
        }
        if (end >= 0 && sb.charAt(end) == ',') {
            sb.deleteCharAt(end);
        }
        while (!stack.isEmpty()) {
            char open = stack.pop();
            sb.append(open == '{' ? '}' : ']');
        }
        return sb.toString();
    }

    public ObjectMapper mapper() {
        return objectMapper;
    }

    public OllamaClient ollama() {
        return ollamaClient;
    }

    public PromptCatalog catalog() {
        return promptCatalog;
    }

    public String getTemplate(String category) {
        return promptCatalog.system(category);
    }

    public String extractJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        Matcher matcher = JSON_BLOCK.matcher(raw);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        int startObj = raw.indexOf('{');
        int startArr = raw.indexOf('[');
        int start;
        if (startObj < 0) {
            start = startArr;
        } else if (startArr < 0) {
            start = startObj;
        } else {
            start = Math.min(startObj, startArr);
        }
        if (start < 0) {
            return raw.trim();
        }
        char open = raw.charAt(start);
        char close = open == '[' ? ']' : '}';
        int end = raw.lastIndexOf(close);
        if (end > start) {
            return raw.substring(start, end + 1);
        }
        return raw.trim();
    }

    public JsonNode itemsNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return objectMapper.createArrayNode();
        }
        if (node.isArray()) {
            return node;
        }
        if (node.has("items") && node.get("items").isArray()) {
            return node.get("items");
        }
        // common alternate keys from models
        for (String key : List.of("scripts", "assets", "shots", "data", "result")) {
            if (node.has(key) && node.get(key).isArray()) {
                return node.get(key);
            }
        }
        return objectMapper.createArrayNode();
    }

    public String text(JsonNode node, String... keys) {
        if (node == null) {
            return "";
        }
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isTextual() || value.isNumber() || value.isBoolean()) {
                return value.asText().trim();
            }
            if (value.isArray()) {
                List<String> parts = new ArrayList<>();
                value.forEach(v -> {
                    String part = v.isTextual() || v.isNumber() ? v.asText() : v.toString();
                    if (!part.isBlank()) {
                        parts.add(part.trim());
                    }
                });
                return String.join("\n", parts);
            }
            if (value.isObject()) {
                return value.toString();
            }
        }
        return "";
    }

    public List<String> stringList(JsonNode node, String... keys) {
        List<String> result = new ArrayList<>();
        if (node == null) {
            return result;
        }
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isArray()) {
                value.forEach(v -> {
                    String part = v.asText("").trim();
                    if (!part.isBlank()) {
                        result.add(part);
                    }
                });
                return result;
            }
            String text = value.asText("").trim();
            if (!text.isBlank()) {
                for (String part : text.split("[,，、/|]")) {
                    if (!part.isBlank()) {
                        result.add(part.trim());
                    }
                }
                return result;
            }
        }
        return result;
    }

    public int intValue(JsonNode node, int defaultValue, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isNumber()) {
                return value.asInt();
            }
            if (value != null && value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText().trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return defaultValue;
    }

    public Integer nullableIntValue(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return value.asInt();
            }
            if (value.isTextual()) {
                String text = value.asText().trim();
                if (text.isBlank() || "null".equalsIgnoreCase(text)) {
                    continue;
                }
                try {
                    return Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    public String quoteJson(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(quoteJson(values.get(i)));
        }
        sb.append(']');
        return sb.toString();
    }
}
