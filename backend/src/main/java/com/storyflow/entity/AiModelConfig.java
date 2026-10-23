package com.storyflow.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_model_configs")
@Getter
@Setter
public class AiModelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String provider = "ollama";

    @Column(nullable = false)
    private String modelName;

    @Column(nullable = false)
    private String baseUrl;

    @Column(columnDefinition = "TEXT")
    private String configJson;

    @Column(nullable = false)
    private boolean active = true;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
