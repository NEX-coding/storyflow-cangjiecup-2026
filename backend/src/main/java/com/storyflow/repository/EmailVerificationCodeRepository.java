package com.storyflow.repository;

import com.storyflow.entity.EmailVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Long> {

    Optional<EmailVerificationCode> findFirstByEmailAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            String email, String purpose, LocalDateTime now);

    Optional<EmailVerificationCode> findFirstByEmailAndPurposeOrderByCreatedAtDesc(String email, String purpose);

    void deleteByExpiresAtBefore(LocalDateTime time);
}
