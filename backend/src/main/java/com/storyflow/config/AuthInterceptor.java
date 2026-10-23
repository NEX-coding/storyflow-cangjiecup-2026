package com.storyflow.config;

import com.storyflow.ai.AiRequestOverride;
import com.storyflow.entity.User;
import com.storyflow.service.AnonymousSessionService;
import com.storyflow.service.AuthService;
import com.storyflow.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String HDR_ENABLED = "X-StoryFlow-AI-Override";
    private static final String HDR_BASE_URL = "X-StoryFlow-AI-Base-Url";
    private static final String HDR_MODEL = "X-StoryFlow-AI-Model";
    private static final String HDR_API_KEY = "X-StoryFlow-AI-Api-Key";
    private static final String HDR_PROVIDER = "X-StoryFlow-AI-Provider";
    private static final String HDR_TEMPERATURE = "X-StoryFlow-AI-Temperature";
    private static final String HDR_MAX_TOKENS = "X-StoryFlow-AI-Max-Tokens";

    private final AuthService authService;
    private final CurrentUserService currentUserService;
    private final AnonymousSessionService anonymousSessionService;
    private final StoryflowProperties properties;

    public AuthInterceptor(AuthService authService,
                           CurrentUserService currentUserService,
                           AnonymousSessionService anonymousSessionService,
                           StoryflowProperties properties) {
        this.authService = authService;
        this.currentUserService = currentUserService;
        this.anonymousSessionService = anonymousSessionService;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = extractToken(request);
        User user = authService.resolveUserByToken(token);
        if (user != null) {
            currentUserService.setRequestUser(user);
        } else if (properties.getAuth().isGuestEnabled()) {
            anonymousSessionService.bindFromRequest(request, response);
        }
        bindAiOverride(request);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        currentUserService.clearRequestUser();
        anonymousSessionService.finishRequest();
        AiRequestOverride.clear();
    }

    private void bindAiOverride(HttpServletRequest request) {
        String enabled = header(request, HDR_ENABLED);
        if (!"1".equals(enabled) && !"true".equalsIgnoreCase(enabled)) {
            AiRequestOverride.clear();
            return;
        }
        String baseUrl = header(request, HDR_BASE_URL);
        String model = header(request, HDR_MODEL);
        if (baseUrl == null || model == null) {
            AiRequestOverride.clear();
            return;
        }
        AiRequestOverride.set(new AiRequestOverride.Override(
                baseUrl,
                model,
                header(request, HDR_API_KEY),
                header(request, HDR_PROVIDER),
                parseDouble(header(request, HDR_TEMPERATURE)),
                parseInt(header(request, HDR_MAX_TOKENS))
        ));
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        String alt = request.getHeader("X-Auth-Token");
        return alt == null ? null : alt.trim();
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Double parseDouble(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer parseInt(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
