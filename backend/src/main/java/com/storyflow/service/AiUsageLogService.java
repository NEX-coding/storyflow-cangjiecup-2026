package com.storyflow.service;

import com.storyflow.common.BusinessException;
import com.storyflow.dto.AiHistoryDtos;
import com.storyflow.entity.AiUsageLog;
import com.storyflow.entity.AssistantMessage;
import com.storyflow.entity.Project;
import com.storyflow.repository.AiUsageLogRepository;
import com.storyflow.repository.AssistantMessageRepository;
import com.storyflow.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AiUsageLogService {

    public static final String TYPE_SCRIPT_GENERATE = "script_generate";
    public static final String TYPE_ASSET_EXTRACT = "asset_extract";
    public static final String TYPE_SHOT_GENERATE = "shot_generate";
    public static final String TYPE_ASSET_REGENERATE = "asset_regenerate";
    public static final String TYPE_SHOT_REGENERATE = "shot_regenerate";
    public static final String TYPE_ASSISTANT_CHAT = "assistant_chat";

    private static final int SUMMARY_MAX = 200;
    private static final int DETAIL_MAX = 500;

    private final AiUsageLogRepository aiUsageLogRepository;
    private final ProjectRepository projectRepository;
    private final AssistantMessageRepository assistantMessageRepository;
    private final CurrentUserService currentUserService;
    private final AnonymousSessionService anonymousSessionService;

    public AiUsageLogService(AiUsageLogRepository aiUsageLogRepository,
                             ProjectRepository projectRepository,
                             AssistantMessageRepository assistantMessageRepository,
                             CurrentUserService currentUserService,
                             AnonymousSessionService anonymousSessionService) {
        this.aiUsageLogRepository = aiUsageLogRepository;
        this.projectRepository = projectRepository;
        this.assistantMessageRepository = assistantMessageRepository;
        this.currentUserService = currentUserService;
        this.anonymousSessionService = anonymousSessionService;
    }

    public List<AiHistoryDtos.ItemResponse> listMine(String type) {
        if (!currentUserService.isAuthenticated()) {
            throw new BusinessException(401, "请先登录后再查看生成历史");
        }
        Long userId = currentUserService.getCurrentUserId();
        List<AiUsageLog> logs = type == null || type.isBlank()
                ? aiUsageLogRepository.findByUserIdOrderByCreatedAtDesc(userId)
                : aiUsageLogRepository.findByUserIdAndTypeOrderByCreatedAtDesc(userId, type.trim());

        Map<Long, String> projectTitles = projectRepository.findAllByOrderByUpdatedAtDesc().stream()
                .collect(Collectors.toMap(Project::getId, Project::getTitle, (a, b) -> a));

        return logs.stream()
                .map(log -> toResponse(log, projectTitles.getOrDefault(log.getProjectId(), "未知项目")))
                .toList();
    }

    @Transactional
    public void logSuccess(Long projectId, String type, String summary, String detail) {
        save(projectId, type, "success", summary, detail);
    }

    @Transactional
    public void logFailure(Long projectId, String type, String summary, String detail) {
        save(projectId, type, "failed", summary, detail);
    }

    @Transactional
    public void backfillFromAssistantMessagesIfEmpty() {
        Long userId = currentUserService.getCurrentUserId();
        if (aiUsageLogRepository.countByUserId(userId) > 0) {
            return;
        }

        List<AssistantMessage> messages = assistantMessageRepository.findAll().stream()
                .filter(m -> userId.equals(m.getUserId()))
                .sorted(Comparator.comparing(AssistantMessage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        AssistantMessage previous = null;
        for (AssistantMessage message : messages) {
            if ("assistant".equals(message.getRole())) {
                String summary = "AI 助手对话";
                if (previous != null
                        && previous.getProjectId().equals(message.getProjectId())
                        && "user".equals(previous.getRole())) {
                    summary = truncate(previous.getContent(), SUMMARY_MAX);
                }
                save(message.getProjectId(), TYPE_ASSISTANT_CHAT, "success", summary, truncate(message.getContent(), DETAIL_MAX));
            }
            previous = message;
        }
    }

    private void save(Long projectId, String type, String status, String summary, String detail) {
        AiUsageLog log = new AiUsageLog();
        log.setProjectId(projectId);
        log.setUserId(currentUserService.getCurrentUserId());
        if (currentUserService.isGuest()) {
            log.setAnonymousSessionId(anonymousSessionService.getCurrentSessionId());
        }
        log.setType(type);
        log.setStatus(status);
        log.setSummary(truncate(summary, SUMMARY_MAX));
        log.setDetail(truncate(detail, DETAIL_MAX));
        aiUsageLogRepository.save(log);
    }

    private AiHistoryDtos.ItemResponse toResponse(AiUsageLog log, String projectTitle) {
        AiHistoryDtos.ItemResponse response = new AiHistoryDtos.ItemResponse();
        response.setId(log.getId());
        response.setProjectId(log.getProjectId());
        response.setProjectTitle(projectTitle);
        response.setType(log.getType());
        response.setStatus(log.getStatus());
        response.setSummary(log.getSummary());
        response.setDetail(log.getDetail());
        response.setCreatedAt(log.getCreatedAt());
        return response;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max) + "…";
    }
}
