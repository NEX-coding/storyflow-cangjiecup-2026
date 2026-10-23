package com.storyflow.repository;

import com.storyflow.entity.AiUsageLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AiUsageLogRepository extends JpaRepository<AiUsageLog, Long> {
    List<AiUsageLog> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<AiUsageLog> findByUserIdAndTypeOrderByCreatedAtDesc(Long userId, String type);

    long countByUserId(Long userId);

    long countByCreatedAtAfter(LocalDateTime since);

    long countByUserIdAndTypeAndCreatedAtAfter(Long userId, String type, LocalDateTime since);

    long countByUserIdAndAnonymousSessionIdAndTypeAndCreatedAtAfter(
            Long userId, String anonymousSessionId, String type, LocalDateTime since);
}
