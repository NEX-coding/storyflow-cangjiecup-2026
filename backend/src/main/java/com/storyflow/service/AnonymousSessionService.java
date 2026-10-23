package com.storyflow.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 网页访客匿名隔离：每个浏览器持有一个 HttpOnly Cookie，配额与项目按此区分。
 */
@Service
public class AnonymousSessionService {

    public static final String COOKIE_NAME = "storyflow-anonymous-id";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);

    private static final ThreadLocal<String> REQUEST_SESSION = new ThreadLocal<>();

    public void bindFromRequest(HttpServletRequest request, HttpServletResponse response) {
        String existing = readCookie(request);
        if (isValidSessionId(existing)) {
            REQUEST_SESSION.set(existing);
            return;
        }
        String created = UUID.randomUUID().toString();
        REQUEST_SESSION.set(created);
        writeCookie(response, request, created);
    }

    public void finishRequest() {
        REQUEST_SESSION.remove();
    }

    public String getCurrentSessionId() {
        return REQUEST_SESSION.get();
    }

    public boolean hasSession() {
        return getCurrentSessionId() != null;
    }

    public boolean matchesProjectSession(String projectAnonymousSessionId) {
        if (projectAnonymousSessionId == null || projectAnonymousSessionId.isBlank()) {
            return false;
        }
        String current = getCurrentSessionId();
        return current != null && current.equals(projectAnonymousSessionId);
    }

    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void writeCookie(HttpServletResponse response, HttpServletRequest request, String sessionId) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, sessionId)
                .httpOnly(true)
                .path(cookiePath(request))
                .maxAge(Duration.ofDays(365))
                .sameSite("Lax")
                .secure(request.isSecure())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** 与前端 /storyflow/web 同站部署时，Path=/ 以便 /storyflow/api 也能带上 Cookie */
    private String cookiePath(HttpServletRequest request) {
        String ctx = request.getContextPath();
        return ctx == null || ctx.isBlank() ? "/" : ctx;
    }

    private boolean isValidSessionId(String value) {
        return value != null && UUID_PATTERN.matcher(value.trim()).matches();
    }
}
