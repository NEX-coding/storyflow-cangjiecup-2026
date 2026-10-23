package com.storyflow.controller;

import com.storyflow.common.ApiResponse;
import com.storyflow.dto.AuthDtos;
import com.storyflow.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register/send-code")
    public ApiResponse<Void> sendRegisterCode(@Valid @RequestBody AuthDtos.SendCodeRequest request) {
        authService.sendRegisterCode(request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/register")
    public ApiResponse<AuthDtos.AuthResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        authService.logout(extractToken(request));
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<AuthDtos.UserInfo> me(HttpServletRequest request) {
        return ApiResponse.ok(authService.me(extractToken(request)));
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return request.getHeader("X-Auth-Token");
    }
}
