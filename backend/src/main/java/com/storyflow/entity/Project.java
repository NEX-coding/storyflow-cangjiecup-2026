package com.storyflow.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
@Getter
@Setter
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** 访客自建项目时绑定的浏览器匿名 Cookie，用于隔离配额与列表 */
    @Column(length = 36)
    private String anonymousSessionId;

    @Column(nullable = false)
    private String title;

    private String stylePreset = "日漫赛璐璐";

    @Column(columnDefinition = "TEXT")
    private String settingsJson;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
