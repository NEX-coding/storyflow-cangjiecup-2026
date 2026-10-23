package com.storyflow.config;

import com.storyflow.ai.PromptCatalog;
import com.storyflow.entity.AdminAccount;
import com.storyflow.entity.AiModelConfig;
import com.storyflow.entity.User;
import com.storyflow.entity.UserRole;
import com.storyflow.repository.AdminAccountRepository;
import com.storyflow.repository.AiModelConfigRepository;
import com.storyflow.repository.AuthSessionRepository;
import com.storyflow.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final AdminAccountRepository adminAccountRepository;
    private final AiModelConfigRepository aiModelConfigRepository;
    private final StoryflowProperties properties;
    private final AuthSessionRepository authSessionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final PromptCatalog promptCatalog;

    public DataInitializer(UserRepository userRepository,
                           AdminAccountRepository adminAccountRepository,
                           AiModelConfigRepository aiModelConfigRepository, StoryflowProperties properties,
                           AuthSessionRepository authSessionRepository,
                           JdbcTemplate jdbcTemplate,
                           PasswordEncoder passwordEncoder,
                           PromptCatalog promptCatalog) {
        this.userRepository = userRepository;
        this.adminAccountRepository = adminAccountRepository;
        this.aiModelConfigRepository = aiModelConfigRepository;
        this.properties = properties;
        this.authSessionRepository = authSessionRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.promptCatalog = promptCatalog;
    }

    @Override
    public void run(String... args) {
        normalizeLegacyReferenceTypes();
        removeLegacyDefaultUser();
        removeLegacyPlatformUser();
        seedAdminAccount();
        seedGuestUser();
        promptCatalog.syncFromClasspath();
        seedAiConfig();
    }

    private void seedAdminAccount() {
        if (!properties.getAuth().isAdminSeedEnabled()) {
            return;
        }
        String username = properties.getAuth().getAdminUsername();
        String password = properties.getAuth().getAdminPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return;
        }
        username = username.trim();
        if (adminAccountRepository.existsByUsernameIgnoreCase(username)) {
            return;
        }

        String nicknameRaw = properties.getAuth().getAdminNickname();
        final String nickname = nicknameRaw == null || nicknameRaw.isBlank() ? username : nicknameRaw.trim();
        String emailRaw = properties.getAuth().getAdminEmail();
        final String internalEmail = (emailRaw == null || emailRaw.isBlank()
                ? username.toLowerCase() + "@storyflow.local"
                : emailRaw).trim().toLowerCase();

        // 兼容旧版邮箱管理员：复用其 users 行，仅补独立账号
        User linked = userRepository.findByEmail("admin@storyflow.com")
                .or(() -> userRepository.findByEmail(internalEmail))
                .orElseGet(() -> {
                    User admin = new User();
                    admin.setEmail(internalEmail);
                    admin.setNickname(nickname);
                    admin.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
                    admin.setRole(UserRole.ADMIN);
                    admin.setEnabled(true);
                    return userRepository.save(admin);
                });

        if (linked.getRole() != UserRole.ADMIN) {
            linked.setRole(UserRole.ADMIN);
            linked.setEnabled(true);
            userRepository.save(linked);
        }

        AdminAccount account = new AdminAccount();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setNickname(nickname);
        account.setUserId(linked.getId());
        account.setEnabled(true);
        adminAccountRepository.save(account);
    }

    private void seedGuestUser() {
        if (!properties.getAuth().isGuestEnabled()) {
            return;
        }
        String email = properties.getAuth().getGuestEmail();
        if (userRepository.existsByEmail(email)) {
            return;
        }
        User guest = new User();
        guest.setEmail(email);
        guest.setNickname(properties.getAuth().getGuestNickname());
        guest.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        guest.setRole(UserRole.USER);
        guest.setEnabled(true);
        userRepository.save(guest);
    }

    /** 移除已下线的叮当/AIP 平台账号会话，避免旧 token 继续登录；保留其项目数据不删 */
    private void removeLegacyPlatformUser() {
        userRepository.findByEmail("aip-platform@storyflow.local").ifPresent(user -> {
            authSessionRepository.deleteByUserId(user.getId());
            if (user.isEnabled()) {
                user.setEnabled(false);
                userRepository.save(user);
            }
        });
    }

    /** Older rows may store enum ordinals as "0"/"1"/"2" instead of names. */
    private void normalizeLegacyReferenceTypes() {
        try {
            jdbcTemplate.update("UPDATE reference_prompts SET type = 'first_frame' WHERE type = '0'");
            jdbcTemplate.update("UPDATE reference_prompts SET type = 'last_frame' WHERE type = '1'");
            jdbcTemplate.update("UPDATE reference_prompts SET type = 'other' WHERE type = '2'");
        } catch (Exception ignored) {
            // table may not exist on first boot
        }
    }

    /** 移除旧版本自动创建的本地默认账号及其登录会话 */
    private void removeLegacyDefaultUser() {
        userRepository.findByEmail("local@storyflow.com").ifPresent(user -> {
            authSessionRepository.deleteByUserId(user.getId());
            try {
                jdbcTemplate.update("DELETE FROM ai_usage_logs WHERE user_id = ?", user.getId());
                jdbcTemplate.update("DELETE FROM feedback WHERE user_id = ?", user.getId());
                jdbcTemplate.update("DELETE FROM assistant_messages WHERE user_id = ?", user.getId());
            } catch (Exception ignored) {
                // tables may not exist on first boot
            }
            userRepository.delete(user);
        });
    }

    private void seedAiConfig() {
        if (aiModelConfigRepository.findFirstByActiveTrueOrderByIdDesc().isEmpty()) {
            AiModelConfig config = new AiModelConfig();
            config.setProvider("ollama");
            config.setBaseUrl(properties.getOllama().getBaseUrl());
            config.setModelName(properties.getOllama().getModel());
            config.setConfigJson("{\"temperature\":0.3,\"maxTokens\":4096}");
            config.setActive(true);
            aiModelConfigRepository.save(config);
        }
    }
}
