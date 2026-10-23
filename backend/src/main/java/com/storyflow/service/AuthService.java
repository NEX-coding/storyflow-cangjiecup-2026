package com.storyflow.service;

import com.storyflow.common.BusinessException;
import com.storyflow.config.StoryflowProperties;
import com.storyflow.dto.AuthDtos;
import com.storyflow.entity.AuthSession;
import com.storyflow.entity.EmailVerificationCode;
import com.storyflow.entity.User;
import com.storyflow.entity.UserRole;
import com.storyflow.repository.AuthSessionRepository;
import com.storyflow.repository.EmailVerificationCodeRepository;
import com.storyflow.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AuthService {

    public static final String PURPOSE_REGISTER = "REGISTER";

    private static final int SESSION_DAYS = 30;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final AuthSessionRepository authSessionRepository;
    private final EmailVerificationCodeRepository codeRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final StoryflowProperties properties;

    public AuthService(UserRepository userRepository,
                       AuthSessionRepository authSessionRepository,
                       EmailVerificationCodeRepository codeRepository,
                       PasswordEncoder passwordEncoder,
                       MailService mailService,
                       StoryflowProperties properties) {
        this.userRepository = userRepository;
        this.authSessionRepository = authSessionRepository;
        this.codeRepository = codeRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.properties = properties;
    }

    @Transactional
    public void sendRegisterCode(AuthDtos.SendCodeRequest request) {
        String email = normalizeEmail(request.getEmail());
        if (email.isBlank()) {
            throw new BusinessException("请输入邮箱");
        }
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException("该邮箱已注册");
        }
        int interval = Math.max(30, properties.getMail().getResendIntervalSeconds());
        codeRepository.findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, PURPOSE_REGISTER)
                .ifPresent(latest -> {
                    if (latest.getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(interval))) {
                        throw new BusinessException("发送过于频繁，请稍后再试");
                    }
                });

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        int ttl = Math.max(5, properties.getMail().getCodeTtlMinutes());
        EmailVerificationCode entity = new EmailVerificationCode();
        entity.setEmail(email);
        entity.setCode(code);
        entity.setPurpose(PURPOSE_REGISTER);
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(ttl));
        entity.setUsed(false);
        entity.setCreatedAt(LocalDateTime.now());
        codeRepository.save(entity);

        mailService.sendRegisterCode(email, code);
        codeRepository.deleteByExpiresAtBefore(LocalDateTime.now().minusDays(1));
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException("该邮箱已注册");
        }
        consumeRegisterCode(email, request.getCode());

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        String nickname = request.getNickname() == null ? "" : request.getNickname().trim();
        if (nickname.isBlank()) {
            nickname = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        }
        user.setNickname(nickname);
        user.setRole(UserRole.USER);
        user.setEnabled(true);
        userRepository.save(user);
        return issueSession(user);
    }

    @Transactional
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        String email = normalizeEmail(request.getEmail());
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(401, "邮箱或密码错误"));
        if (!user.isEnabled()) {
            throw new BusinessException(403, "账号已停用");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(401, "邮箱或密码错误");
        }
        return issueSession(user);
    }

    @Transactional
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        authSessionRepository.deleteById(token);
    }

    public AuthDtos.UserInfo me(String token) {
        User user = resolveUserByToken(token);
        if (user == null && properties.getAuth().isGuestEnabled()) {
            user = userRepository.findByEmail(properties.getAuth().getGuestEmail())
                    .filter(User::isEnabled)
                    .orElse(null);
        }
        if (user == null) {
            throw new BusinessException(401, "未登录或登录已过期");
        }
        return toUserInfo(user);
    }

    public User resolveUserByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return authSessionRepository.findByTokenAndExpiresAtAfter(token, LocalDateTime.now())
                .flatMap(session -> userRepository.findById(session.getUserId()))
                .filter(User::isEnabled)
                .orElse(null);
    }

    private void consumeRegisterCode(String email, String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.isBlank()) {
            throw new BusinessException("请输入邮箱验证码");
        }
        EmailVerificationCode record = codeRepository
                .findFirstByEmailAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                        email, PURPOSE_REGISTER, LocalDateTime.now())
                .orElseThrow(() -> new BusinessException("验证码无效或已过期，请重新获取"));
        if (!record.getCode().equals(code)) {
            throw new BusinessException("验证码错误");
        }
        record.setUsed(true);
        codeRepository.save(record);
    }

    private User requireUserByToken(String token) {
        User user = resolveUserByToken(token);
        if (user == null) {
            throw new BusinessException(401, "未登录或登录已过期");
        }
        return user;
    }

    private AuthDtos.AuthResponse issueSession(User user) {
        authSessionRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        String token = UUID.randomUUID().toString().replace("-", "");
        AuthSession session = new AuthSession();
        session.setToken(token);
        session.setUserId(user.getId());
        session.setExpiresAt(LocalDateTime.now().plusDays(SESSION_DAYS));
        authSessionRepository.save(session);

        AuthDtos.AuthResponse resp = new AuthDtos.AuthResponse();
        resp.setToken(token);
        resp.setUser(toUserInfo(user));
        return resp;
    }

    private AuthDtos.UserInfo toUserInfo(User user) {
        AuthDtos.UserInfo info = new AuthDtos.UserInfo();
        info.setId(user.getId());
        info.setEmail(user.getEmail());
        info.setNickname(user.getNickname());
        info.setRole(user.getRole() == null ? UserRole.USER.name() : user.getRole().name());
        info.setGuest(isGuestEmail(user.getEmail()));
        return info;
    }

    private boolean isGuestEmail(String email) {
        if (email == null) {
            return false;
        }
        return email.trim().equalsIgnoreCase(properties.getAuth().getGuestEmail());
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
