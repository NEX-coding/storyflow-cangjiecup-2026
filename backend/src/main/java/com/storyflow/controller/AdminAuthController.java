package com.storyflow.controller;

import com.storyflow.common.ApiResponse;
import com.storyflow.dto.AdminAuthDtos;
import com.storyflow.service.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @PostMapping("/login")
    public ApiResponse<AdminAuthDtos.AuthResponse> login(@Valid @RequestBody AdminAuthDtos.LoginRequest request) {
        return ApiResponse.ok(adminAuthService.login(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        adminAuthService.logout(extractToken(request));
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<AdminAuthDtos.AdminInfo> me(HttpServletRequest request) {
        return ApiResponse.ok(adminAuthService.me(extractToken(request)));
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        String alt = request.getHeader("X-Auth-Token");
        return alt == null ? null : alt.trim();
    }
}
