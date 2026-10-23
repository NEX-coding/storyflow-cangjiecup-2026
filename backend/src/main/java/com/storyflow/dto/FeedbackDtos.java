package com.storyflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

public class FeedbackDtos {

    @Data
    public static class CreateRequest {
        @NotBlank(message = "请选择反馈类型")
        private String type;

        @NotBlank(message = "请填写反馈内容")
        @Size(max = 2000, message = "反馈内容不能超过 2000 字")
        private String content;

        @Size(max = 128, message = "联系方式不能超过 128 字")
        private String contact;

        @Size(max = 512, message = "页面地址过长")
        private String pageUrl;
    }

    @Data
    public static class FeedbackResponse {
        private Long id;
        private String type;
        private String content;
        private String contact;
        private String pageUrl;
        private String status;
        private String adminReply;
        private LocalDateTime handledAt;
        private LocalDateTime createdAt;
    }

    @Data
    public static class AdminFeedbackItem {
        private Long id;
        private Long userId;
        private String userEmail;
        private String userNickname;
        private String type;
        private String content;
        private String contact;
        private String pageUrl;
        private String status;
        private String adminReply;
        private LocalDateTime handledAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class AdminFeedbackPage {
        private List<AdminFeedbackItem> items;
        private long total;
        private int page;
        private int size;
    }

    @Data
    public static class HandleRequest {
        @NotBlank(message = "请选择处理状态")
        private String status;

        @Size(max = 2000, message = "回复内容不能超过 2000 字")
        private String adminReply;
    }
}
