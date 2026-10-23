package com.storyflow.repository;

import com.storyflow.entity.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AuthSessionRepository extends JpaRepository<AuthSession, String> {
    Optional<AuthSession> findByTokenAndExpiresAtAfter(String token, LocalDateTime now);

    void deleteByUserId(Long userId);

    void deleteByExpiresAtBefore(LocalDateTime time);
}
