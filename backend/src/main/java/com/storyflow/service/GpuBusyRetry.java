package com.storyflow.service;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Supplier;

/**
 * GPU 文生图/文生视频服务单线程，429 busy 时自动排队重试。
 * 503 / unavailable 视为服务宕机，立即失败，交由 watchdog 拉起，勿空等数分钟。
 */
final class GpuBusyRetry {

    private static final int MAX_ATTEMPTS = 120;
    private static final long WAIT_MS = 2_000;

    private GpuBusyRetry() {}

    static String postJson(RestClient.RequestBodySpec spec) {
        return withBusyRetry(() -> spec.retrieve().body(String.class));
    }

    static <T> T withBusyRetry(Supplier<T> action) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (RestClientResponseException ex) {
                if (isUnavailable(ex)) {
                    throw new IllegalStateException(
                            "生图服务不可用（Comfy/API 未就绪），请稍后重试。"
                                    + " detail="
                                    + truncate(ex.getResponseBodyAsString()),
                            ex);
                }
                if (isBusy(ex) && attempt < MAX_ATTEMPTS - 1) {
                    sleep();
                    continue;
                }
                throw ex;
            } catch (RuntimeException ex) {
                if (isUnavailableMessage(ex.getMessage())) {
                    throw ex instanceof IllegalStateException
                            ? ex
                            : new IllegalStateException(
                                    "生图服务不可用：" + truncate(ex.getMessage()), ex);
                }
                if (isBusyMessage(ex.getMessage()) && attempt < MAX_ATTEMPTS - 1) {
                    sleep();
                    continue;
                }
                throw ex;
            }
        }
        throw new IllegalStateException("GPU busy retry exhausted");
    }

    private static boolean isBusy(RestClientResponseException ex) {
        if (ex.getStatusCode().value() == 429) {
            return true;
        }
        return isBusyMessage(ex.getResponseBodyAsString());
    }

    private static boolean isUnavailable(RestClientResponseException ex) {
        int code = ex.getStatusCode().value();
        if (code == 503 || code == 502 || code == 504) {
            return true;
        }
        return isUnavailableMessage(ex.getResponseBodyAsString());
    }

    private static boolean isBusyMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        // 勿把 "unavailable" 误判为 busy
        if (isUnavailableMessage(message)) {
            return false;
        }
        return message.contains("429")
                || message.contains("busy")
                || message.contains("another generation in progress");
    }

    private static boolean isUnavailableMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("503")
                || lower.contains("unavailable")
                || lower.contains("not reachable")
                || lower.contains("comfy unavailable")
                || lower.contains("connection refused")
                || lower.contains("failed to connect");
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() > 240 ? t.substring(0, 240) + "…" : t;
    }

    private static void sleep() {
        try {
            Thread.sleep(WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for GPU", e);
        }
    }
}
