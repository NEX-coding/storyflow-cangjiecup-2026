package com.storyflow.ai;

/**
 * 请求级 AI 配置覆盖：仅存于当前线程，不落库。
 * 用于用户本机填写的 DeepSeek / OpenAI 等 Key，随请求头传入后临时生效。
 */
public final class AiRequestOverride {

    private static final ThreadLocal<Override> HOLDER = new ThreadLocal<>();

    private AiRequestOverride() {
    }

    public static void set(Override override) {
        if (override == null || !override.isUsable()) {
            HOLDER.remove();
            return;
        }
        HOLDER.set(override);
    }

    public static Override get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public record Override(
            String baseUrl,
            String modelName,
            String apiKey,
            String provider,
            Double temperature,
            Integer maxTokens
    ) {
        public boolean isUsable() {
            return baseUrl != null && !baseUrl.isBlank()
                    && modelName != null && !modelName.isBlank();
        }
    }
}
