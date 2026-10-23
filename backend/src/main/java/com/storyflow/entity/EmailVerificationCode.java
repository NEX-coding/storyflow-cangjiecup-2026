package com.storyflow.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "email_verification_codes", indexes = {
        @Index(name = "idx_email_code_email_purpose", columnList = "email,purpose")
})
public class EmailVerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String email;

    @Column(nullable = false, length = 16)
    private String code;

    @Column(nullable = false, length = 32)
    private String purpose;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private boolean used = false;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
