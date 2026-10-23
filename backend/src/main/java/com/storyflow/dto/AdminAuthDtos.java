package com.storyflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

public class AdminAuthDtos {

    @Data
    public static class LoginRequest {
        @NotBlank
        @Size(min = 2, max = 64)
        private String username;

        @NotBlank
        @Size(min = 4, max = 64)
        private String password;
    }

    @Data
    public static class AuthResponse {
        private String token;
        private AdminInfo admin;
    }

    @Data
    public static class AdminInfo {
        private Long id;
        private String username;
        private String nickname;
        private String role;
    }
}
