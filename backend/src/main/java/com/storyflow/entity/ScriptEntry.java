package com.storyflow.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "scripts")
@Getter
@Setter
public class ScriptEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    private String timeLabel;
    private String location;

    @Column(columnDefinition = "TEXT")
    private String charactersJson;

    @Column(columnDefinition = "TEXT")
    private String action;

    @Column(columnDefinition = "TEXT")
    private String dialogue;

    @Column(columnDefinition = "TEXT")
    private String shot;

    @Column(nullable = false)
    private int orderIndex;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
