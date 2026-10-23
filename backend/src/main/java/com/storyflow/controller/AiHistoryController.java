package com.storyflow.controller;

import com.storyflow.common.ApiResponse;
import com.storyflow.dto.AiHistoryDtos;
import com.storyflow.service.AiUsageLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai-history")
public class AiHistoryController {

    private final AiUsageLogService aiUsageLogService;

    public AiHistoryController(AiUsageLogService aiUsageLogService) {
        this.aiUsageLogService = aiUsageLogService;
    }

    @GetMapping
    public ApiResponse<List<AiHistoryDtos.ItemResponse>> listMine(@RequestParam(required = false) String type) {
        return ApiResponse.ok(aiUsageLogService.listMine(type));
    }
}
