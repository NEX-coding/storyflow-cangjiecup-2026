package com.storyflow.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "project_snapshots")
@Getter
@Setter
public class ProjectSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 64)
    private String kind;

    @Column(nullable = false, length = 256)
    private String label;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
