package com.storyflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "auth_sessions")
@Getter
@Setter
public class AuthSession {

    @Id
    @Column(length = 64)
    private String token;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
