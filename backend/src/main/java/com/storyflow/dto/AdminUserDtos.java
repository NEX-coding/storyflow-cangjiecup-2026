package com.storyflow.dto;

import com.storyflow.entity.UserRole;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

public class AdminUserDtos {

    @Data
    public static class UserSummary {
        private Long id;
        private String email;
        private String nickname;
        private UserRole role;
        private boolean enabled;
        private LocalDateTime createdAt;
        private long projectCount;
        private long aiCallCount;
        private boolean systemAccount;
    }

    @Data
    public static class UserPage {
        private List<UserSummary> items;
        private long total;
        private int page;
        private int size;
    }

    @Data
    public static class UpdateStatusRequest {
        private boolean enabled;
    }
}
