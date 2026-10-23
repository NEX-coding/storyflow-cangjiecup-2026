package com.storyflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "reference_prompts")
@Getter
@Setter
public class ReferencePrompt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shot_id", nullable = false)
    private Shot shot;

    @Convert(converter = ReferenceTypeConverter.class)
    @Column(nullable = false)
    private ReferenceType type = ReferenceType.other;

    @Column(columnDefinition = "TEXT")
    private String prompt;

    private String note;

    /** 分镜参考图（由 referencePrompt 生成，归档到 edu 媒体库） */
    @Column(length = 255)
    private String referenceImageFile;

    @Column(nullable = false)
    private int orderIndex;
}
