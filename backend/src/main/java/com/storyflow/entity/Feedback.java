package com.storyflow.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "feedbacks")
@Getter
@Setter
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(length = 128)
    private String contact;

    @Column(length = 512)
    private String pageUrl;

    /** pending / processing / resolved / closed */
    @Column(nullable = false, length = 32)
    private String status = "pending";

    @Column(columnDefinition = "TEXT")
    private String adminReply;

    private LocalDateTime handledAt;

    private Long handledByAdminId;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
