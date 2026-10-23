package com.storyflow.controller;

import com.storyflow.common.ApiResponse;
import com.storyflow.dto.FeedbackDtos;
import com.storyflow.service.FeedbackService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @GetMapping
    public ApiResponse<List<FeedbackDtos.FeedbackResponse>> listMine() {
        return ApiResponse.ok(feedbackService.listMine());
    }

    @PostMapping
    public ApiResponse<FeedbackDtos.FeedbackResponse> submit(@Valid @RequestBody FeedbackDtos.CreateRequest request) {
        return ApiResponse.ok("反馈已提交", feedbackService.submit(request));
    }
}
