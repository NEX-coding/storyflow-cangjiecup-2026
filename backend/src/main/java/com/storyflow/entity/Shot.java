package com.storyflow.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "shots")
@Getter
@Setter
public class Shot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    private Long scriptId;

    @Column(nullable = false)
    private int orderIndex;

    @Column(columnDefinition = "TEXT")
    private String videoPrompt;

    @Column(columnDefinition = "TEXT")
    private String linkedAssetIdsJson;

    /**
     * 分镜视频参考方式：first_last | single | none；空则继承作品默认。
     * 旧值 storyboard 视为 single。
     */
    @Column(length = 32)
    private String videoRefMode;

    @OneToMany(mappedBy = "shot", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    private List<ReferencePrompt> referencePrompts = new ArrayList<>();

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
