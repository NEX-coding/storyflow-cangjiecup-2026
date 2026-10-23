package com.storyflow.assistant;

import com.storyflow.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 助手独立接口模块。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/assistant")
public class AssistantController {

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @PostMapping("/chat")
    public ApiResponse<AssistantDtos.MessageResponse> chat(@PathVariable Long projectId,
                                                           @Valid @RequestBody AssistantDtos.ChatRequest req) {
        return ApiResponse.ok(assistantService.chat(projectId, req));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@PathVariable Long projectId,
                                 @Valid @RequestBody AssistantDtos.ChatRequest req,
                                 HttpServletResponse response) {
        // 告知 nginx 不要缓冲 SSE，否则前端会长时间停在「连接中…」
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");
        return assistantService.chatStream(projectId, req);
    }

    @GetMapping("/history")
    public ApiResponse<List<AssistantDtos.MessageResponse>> history(@PathVariable Long projectId) {
        return ApiResponse.ok(assistantService.history(projectId));
    }
}
