package com.storyflow.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

public class AuthDtos {

    @Data
    public static class SendCodeRequest {
        @NotBlank
        @Email
        private String email;
    }

    @Data
    public static class RegisterRequest {
        @NotBlank
        @Email
        private String email;

        @NotBlank
        @Size(min = 6, max = 8)
        private String code;

        @NotBlank
        @Size(min = 6, max = 64)
        private String password;

        @Size(max = 40)
        private String nickname;
    }

    @Data
    public static class LoginRequest {
        @NotBlank
        @Email
        private String email;

        @NotBlank
        private String password;
    }

    @Data
    public static class AuthResponse {
        private String token;
        private UserInfo user;
    }

    @Data
    public static class UserInfo {
        private Long id;
        private String email;
        private String nickname;
        private String role;
        /** 网页访客（共享 guest 账号），非真实登录 */
        private boolean guest;
    }
}
