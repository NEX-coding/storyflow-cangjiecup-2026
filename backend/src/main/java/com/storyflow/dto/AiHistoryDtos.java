package com.storyflow.dto;

import lombok.Data;

import java.time.LocalDateTime;

public class AiHistoryDtos {

    @Data
    public static class ItemResponse {
        private Long id;
        private Long projectId;
        private String projectTitle;
        private String type;
        private String status;
        private String summary;
        private String detail;
        private LocalDateTime createdAt;
    }
}
