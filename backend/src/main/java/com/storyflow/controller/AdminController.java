package com.storyflow.controller;

import com.storyflow.common.ApiResponse;
import com.storyflow.dto.AdminUserDtos;
import com.storyflow.dto.FeedbackDtos;
import com.storyflow.entity.AiModelConfig;
import com.storyflow.entity.PromptTemplate;
import com.storyflow.service.AdminService;
import com.storyflow.service.FeedbackService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final FeedbackService feedbackService;

    public AdminController(AdminService adminService, FeedbackService feedbackService) {
        this.adminService = adminService;
        this.feedbackService = feedbackService;
    }

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        return ApiResponse.ok(adminService.dashboard());
    }

    @GetMapping("/users")
    public ApiResponse<AdminUserDtos.UserPage> listUsers(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminService.listUsers(q, role, enabled, page, size));
    }

    @GetMapping("/users/{id}")
    public ApiResponse<AdminUserDtos.UserSummary> getUser(@PathVariable Long id) {
        return ApiResponse.ok(adminService.getUser(id));
    }

    @PutMapping("/users/{id}/status")
    public ApiResponse<AdminUserDtos.UserSummary> updateUserStatus(
            @PathVariable Long id,
            @RequestBody AdminUserDtos.UpdateStatusRequest request) {
        return ApiResponse.ok(adminService.updateUserStatus(id, request.isEnabled()));
    }

    @GetMapping("/feedbacks")
    public ApiResponse<FeedbackDtos.AdminFeedbackPage> listFeedbacks(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(feedbackService.adminList(q, type, status, page, size));
    }

    @PutMapping("/feedbacks/{id}")
    public ApiResponse<FeedbackDtos.AdminFeedbackItem> handleFeedback(
            @PathVariable Long id,
            @Valid @RequestBody FeedbackDtos.HandleRequest request) {
        return ApiResponse.ok(feedbackService.adminHandle(id, request));
    }

    @GetMapping("/prompt-templates")
    public ApiResponse<List<PromptTemplate>> listTemplates() {
        return ApiResponse.ok(adminService.listTemplates());
    }

    @PostMapping("/prompt-templates")
    public ApiResponse<PromptTemplate> createTemplate(@RequestBody PromptTemplate template) {
        return ApiResponse.ok(adminService.saveTemplate(template));
    }

    @PutMapping("/prompt-templates/{id}")
    public ApiResponse<PromptTemplate> updateTemplate(@PathVariable Long id, @RequestBody PromptTemplate template) {
        template.setId(id);
        return ApiResponse.ok(adminService.saveTemplate(template));
    }

    @GetMapping("/ai-models")
    public ApiResponse<AiModelConfig> getAiConfig() {
        return ApiResponse.ok(adminService.getAiConfig());
    }

    @PutMapping("/ai-models")
    public ApiResponse<AiModelConfig> saveAiConfig(@RequestBody AdminService.AiConfigRequest request) {
        return ApiResponse.ok(adminService.saveAiConfig(request));
    }

    @GetMapping("/ai-models/health")
    public ApiResponse<Map<String, Object>> aiHealth() {
        return ApiResponse.ok(adminService.aiHealth());
    }

    @GetMapping("/media-models")
    public ApiResponse<Map<String, Object>> getMediaConfig() {
        return ApiResponse.ok(adminService.getMediaConfig());
    }

    @PutMapping("/media-models")
    public ApiResponse<Map<String, Object>> saveMediaConfig(
            @RequestBody AdminService.MediaConfigRequest request) {
        return ApiResponse.ok(adminService.saveMediaConfig(request));
    }

    @GetMapping("/media-models/health")
    public ApiResponse<Map<String, Object>> mediaHealth() {
        return ApiResponse.ok(adminService.mediaHealth());
    }
}
