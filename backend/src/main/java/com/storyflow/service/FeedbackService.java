package com.storyflow.service;

import com.storyflow.common.BusinessException;
import com.storyflow.dto.FeedbackDtos;
import com.storyflow.entity.Feedback;
import com.storyflow.entity.User;
import com.storyflow.entity.UserRole;
import com.storyflow.repository.FeedbackRepository;
import com.storyflow.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FeedbackService {

    private static final Set<String> ALLOWED_TYPES = Set.of("bug", "feature", "improvement", "other");
    private static final Set<String> ALLOWED_STATUS = Set.of("pending", "processing", "resolved", "closed");

    private final FeedbackRepository feedbackRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;

    public FeedbackService(FeedbackRepository feedbackRepository,
                           UserRepository userRepository,
                           CurrentUserService currentUserService) {
        this.feedbackRepository = feedbackRepository;
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
    }

    public List<FeedbackDtos.FeedbackResponse> listMine() {
        Long userId = currentUserService.getCurrentUserId();
        return feedbackRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public FeedbackDtos.FeedbackResponse submit(FeedbackDtos.CreateRequest request) {
        String type = request.getType() == null ? "" : request.getType().trim();
        if (!ALLOWED_TYPES.contains(type)) {
            throw new BusinessException("无效的反馈类型");
        }

        Feedback feedback = new Feedback();
        feedback.setUserId(currentUserService.getCurrentUserId());
        feedback.setType(type);
        feedback.setContent(request.getContent().trim());
        feedback.setContact(trimToNull(request.getContact()));
        feedback.setPageUrl(trimToNull(request.getPageUrl()));
        feedback.setStatus("pending");
        return toResponse(feedbackRepository.save(feedback));
    }

    public FeedbackDtos.AdminFeedbackPage adminList(String q, String type, String status, int page, int size) {
        requireAdmin();
        String typeFilter = normalizeFilter(type, ALLOWED_TYPES, "无效的反馈类型筛选");
        String statusFilter = normalizeFilter(status, ALLOWED_STATUS, "无效的处理状态筛选");
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String keyword = q == null ? "" : q.trim();

        Page<Feedback> result = feedbackRepository.search(
                keyword,
                typeFilter,
                statusFilter,
                PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));

        Map<Long, User> users = userRepository.findAllById(
                        result.getContent().stream().map(Feedback::getUserId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));

        FeedbackDtos.AdminFeedbackPage body = new FeedbackDtos.AdminFeedbackPage();
        body.setItems(result.getContent().stream()
                .map(item -> toAdminItem(item, users.get(item.getUserId())))
                .toList());
        body.setTotal(result.getTotalElements());
        body.setPage(safePage);
        body.setSize(safeSize);
        return body;
    }

    @Transactional
    public FeedbackDtos.AdminFeedbackItem adminHandle(Long id, FeedbackDtos.HandleRequest request) {
        requireAdmin();
        Feedback feedback = feedbackRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "反馈不存在"));

        String status = request.getStatus() == null ? "" : request.getStatus().trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_STATUS.contains(status)) {
            throw new BusinessException("无效的处理状态");
        }

        feedback.setStatus(status);
        feedback.setAdminReply(trimToNull(request.getAdminReply()));
        feedback.setHandledAt(LocalDateTime.now());
        User admin = currentUserService.getRequestUser();
        if (admin != null) {
            feedback.setHandledByAdminId(admin.getId());
        }
        feedbackRepository.save(feedback);

        User user = userRepository.findById(feedback.getUserId()).orElse(null);
        return toAdminItem(feedback, user);
    }

    public long countPending() {
        return feedbackRepository.countByStatus("pending");
    }

    private void requireAdmin() {
        if (currentUserService.isGuest()) {
            throw new BusinessException(403, "需要管理员权限");
        }
        User user = currentUserService.getRequestUser();
        if (user == null || user.getRole() != UserRole.ADMIN) {
            throw new BusinessException(403, "需要管理员权限");
        }
    }

    private FeedbackDtos.FeedbackResponse toResponse(Feedback feedback) {
        FeedbackDtos.FeedbackResponse response = new FeedbackDtos.FeedbackResponse();
        response.setId(feedback.getId());
        response.setType(feedback.getType());
        response.setContent(feedback.getContent());
        response.setContact(feedback.getContact());
        response.setPageUrl(feedback.getPageUrl());
        response.setStatus(feedback.getStatus() == null ? "pending" : feedback.getStatus());
        response.setAdminReply(feedback.getAdminReply());
        response.setHandledAt(feedback.getHandledAt());
        response.setCreatedAt(feedback.getCreatedAt());
        return response;
    }

    private FeedbackDtos.AdminFeedbackItem toAdminItem(Feedback feedback, User user) {
        FeedbackDtos.AdminFeedbackItem item = new FeedbackDtos.AdminFeedbackItem();
        item.setId(feedback.getId());
        item.setUserId(feedback.getUserId());
        if (user != null) {
            item.setUserEmail(user.getEmail());
            item.setUserNickname(user.getNickname());
        }
        item.setType(feedback.getType());
        item.setContent(feedback.getContent());
        item.setContact(feedback.getContact());
        item.setPageUrl(feedback.getPageUrl());
        item.setStatus(feedback.getStatus() == null ? "pending" : feedback.getStatus());
        item.setAdminReply(feedback.getAdminReply());
        item.setHandledAt(feedback.getHandledAt());
        item.setCreatedAt(feedback.getCreatedAt());
        item.setUpdatedAt(feedback.getUpdatedAt());
        return item;
    }

    private String normalizeFilter(String value, Set<String> allowed, String errorMessage) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BusinessException(errorMessage);
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
