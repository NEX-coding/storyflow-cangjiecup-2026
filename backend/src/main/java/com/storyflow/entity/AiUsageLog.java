package com.storyflow.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_usage_logs")
@Getter
@Setter
public class AiUsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long userId;

    @Column(length = 36)
    private String anonymousSessionId;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(nullable = false, length = 16)
    private String status = "success";

    @Column(nullable = false, length = 256)
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
