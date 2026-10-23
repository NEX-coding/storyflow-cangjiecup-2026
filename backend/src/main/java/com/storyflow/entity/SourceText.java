package com.storyflow.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "source_texts")
@Getter
@Setter
public class SourceText {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long projectId;

    @Column(columnDefinition = "TEXT")
    private String content;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
