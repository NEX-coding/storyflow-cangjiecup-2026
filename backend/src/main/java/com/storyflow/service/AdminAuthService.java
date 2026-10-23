package com.storyflow.service;

import com.storyflow.common.BusinessException;
import com.storyflow.dto.AdminAuthDtos;
import com.storyflow.entity.AdminAccount;
import com.storyflow.entity.AuthSession;
import com.storyflow.entity.User;
import com.storyflow.entity.UserRole;
import com.storyflow.repository.AdminAccountRepository;
import com.storyflow.repository.AuthSessionRepository;
import com.storyflow.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AdminAuthService {

    private static final int SESSION_DAYS = 30;

    private final AdminAccountRepository adminAccountRepository;
    private final UserRepository userRepository;
    private final AuthSessionRepository authSessionRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminAuthService(AdminAccountRepository adminAccountRepository,
                            UserRepository userRepository,
                            AuthSessionRepository authSessionRepository,
                            PasswordEncoder passwordEncoder) {
        this.adminAccountRepository = adminAccountRepository;
        this.userRepository = userRepository;
        this.authSessionRepository = authSessionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AdminAuthDtos.AuthResponse login(AdminAuthDtos.LoginRequest request) {
        String username = normalizeUsername(request.getUsername());
        AdminAccount account = adminAccountRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new BusinessException(401, "账号或密码错误"));
        if (!account.isEnabled()) {
            throw new BusinessException(403, "账号已停用");
        }
        if (!passwordEncoder.matches(request.getPassword(), account.getPasswordHash())) {
            throw new BusinessException(401, "账号或密码错误");
        }
        User user = requireAdminUser(account.getUserId());
        return issueSession(account, user);
    }

    @Transactional
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        authSessionRepository.deleteById(token);
    }

    public AdminAuthDtos.AdminInfo me(String token) {
        User user = resolveAdminUserByToken(token);
        AdminAccount account = adminAccountRepository.findByUserId(user.getId())
                .orElseThrow(() -> new BusinessException(401, "未登录或登录已过期"));
        if (!account.isEnabled()) {
            throw new BusinessException(403, "账号已停用");
        }
        return toAdminInfo(account);
    }

    private User resolveAdminUserByToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(401, "未登录或登录已过期");
        }
        return authSessionRepository.findByTokenAndExpiresAtAfter(token, LocalDateTime.now())
                .flatMap(session -> userRepository.findById(session.getUserId()))
                .filter(User::isEnabled)
                .filter(u -> u.getRole() == UserRole.ADMIN)
                .orElseThrow(() -> new BusinessException(401, "未登录或登录已过期"));
    }

    private User requireAdminUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(401, "账号或密码错误"));
        if (!user.isEnabled() || user.getRole() != UserRole.ADMIN) {
            throw new BusinessException(403, "账号无权登录管理端");
        }
        return user;
    }

    private AdminAuthDtos.AuthResponse issueSession(AdminAccount account, User user) {
        authSessionRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        String token = UUID.randomUUID().toString().replace("-", "");
        AuthSession session = new AuthSession();
        session.setToken(token);
        session.setUserId(user.getId());
        session.setExpiresAt(LocalDateTime.now().plusDays(SESSION_DAYS));
        authSessionRepository.save(session);

        AdminAuthDtos.AuthResponse resp = new AdminAuthDtos.AuthResponse();
        resp.setToken(token);
        resp.setAdmin(toAdminInfo(account));
        return resp;
    }

    private AdminAuthDtos.AdminInfo toAdminInfo(AdminAccount account) {
        AdminAuthDtos.AdminInfo info = new AdminAuthDtos.AdminInfo();
        info.setId(account.getId());
        info.setUsername(account.getUsername());
        info.setNickname(account.getNickname());
        info.setRole(UserRole.ADMIN.name());
        return info;
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim();
    }
}
