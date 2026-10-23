package com.storyflow.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "admin_accounts")
@Getter
@Setter
public class AdminAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 64)
    private String nickname;

    /** 关联 users 表中的 ADMIN 用户，复用现有 Session 鉴权 */
    @Column(nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false)
    private boolean enabled = true;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
