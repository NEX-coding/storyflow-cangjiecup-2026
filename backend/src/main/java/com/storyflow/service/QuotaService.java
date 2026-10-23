package com.storyflow.service;

import com.storyflow.common.BusinessException;
import com.storyflow.config.StoryflowProperties;
import com.storyflow.repository.AiUsageLogRepository;
import com.storyflow.repository.ProjectRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class QuotaService {

    private final StoryflowProperties properties;
    private final CurrentUserService currentUserService;
    private final AnonymousSessionService anonymousSessionService;
    private final AiUsageLogRepository aiUsageLogRepository;
    private final ProjectRepository projectRepository;

    public QuotaService(StoryflowProperties properties,
                        CurrentUserService currentUserService,
                        AnonymousSessionService anonymousSessionService,
                        AiUsageLogRepository aiUsageLogRepository,
                        ProjectRepository projectRepository) {
        this.properties = properties;
        this.currentUserService = currentUserService;
        this.anonymousSessionService = anonymousSessionService;
        this.aiUsageLogRepository = aiUsageLogRepository;
        this.projectRepository = projectRepository;
    }

    public void checkProjectCreate() {
        if (!currentUserService.isGuest()) {
            return;
        }
        String sessionId = requireAnonymousSession();
        Long userId = currentUserService.getCurrentUserId();
        long count = projectRepository.countByUserIdAndAnonymousSessionId(userId, sessionId);
        int max = properties.getQuota().getGuest().getMaxProjects();
        if (count >= max) {
            throw new BusinessException(429, "访客最多创建 " + max + " 个项目，请登录后继续使用");
        }
    }

    public void checkAiUsage(String type) {
        if (!currentUserService.isGuest()) {
            return;
        }
        int limit = limitFor(type);
        if (limit < 0) {
            return;
        }
        String sessionId = requireAnonymousSession();
        Long userId = currentUserService.getCurrentUserId();
        LocalDateTime since = LocalDate.now().atStartOfDay();
        long used = aiUsageLogRepository.countByUserIdAndAnonymousSessionIdAndTypeAndCreatedAtAfter(
                userId, sessionId, type, since);
        if (used >= limit) {
            throw new BusinessException(429, guestQuotaMessage(type, limit));
        }
    }

    public String currentAnonymousSessionId() {
        return anonymousSessionService.getCurrentSessionId();
    }

    private String requireAnonymousSession() {
        String sessionId = anonymousSessionService.getCurrentSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(401, "访客会话无效，请刷新页面后重试");
        }
        return sessionId;
    }

    private int limitFor(String type) {
        StoryflowProperties.Quota.Guest guest = properties.getQuota().getGuest();
        return switch (type) {
            case AiUsageLogService.TYPE_SCRIPT_GENERATE -> guest.getDailyScriptGenerate();
            case AiUsageLogService.TYPE_ASSET_EXTRACT -> guest.getDailyAssetExtract();
            case AiUsageLogService.TYPE_SHOT_GENERATE -> guest.getDailyShotsGenerate();
            case AiUsageLogService.TYPE_ASSISTANT_CHAT -> guest.getDailyAssistantChat();
            default -> -1;
        };
    }

    private String guestQuotaMessage(String type, int limit) {
        String action = switch (type) {
            case AiUsageLogService.TYPE_SCRIPT_GENERATE -> "生成剧本";
            case AiUsageLogService.TYPE_ASSET_EXTRACT -> "提取资产";
            case AiUsageLogService.TYPE_SHOT_GENERATE -> "生成分镜";
            case AiUsageLogService.TYPE_ASSISTANT_CHAT -> "使用 AI 助手";
            default -> "调用 AI";
        };
        return "访客今日" + action + "次数已达上限（" + limit + " 次），请登录后继续使用";
    }
}
