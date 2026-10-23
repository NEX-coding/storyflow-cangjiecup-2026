package com.storyflow.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "shot_videos")
@Getter
@Setter
public class ShotVideo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long shotId;

    @Column(nullable = false, length = 32)
    private String status = "ready";

    @Column(columnDefinition = "TEXT")
    private String prompt;

    @Column(length = 255)
    private String remoteFile;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private String providerTaskId;

    private Double elapsedSec;
    private Integer frames;
    private Integer width;
    private Integer height;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
