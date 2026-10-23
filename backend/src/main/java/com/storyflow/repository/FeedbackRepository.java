package com.storyflow.repository;

import com.storyflow.entity.Feedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    List<Feedback> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByStatus(String status);

    @Query("""
            SELECT f FROM Feedback f
            WHERE (:type IS NULL OR :type = '' OR f.type = :type)
              AND (:status IS NULL OR :status = '' OR f.status = :status)
              AND (:q IS NULL OR :q = ''
                OR LOWER(f.content) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(f.contact, '')) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<Feedback> search(@Param("q") String q,
                          @Param("type") String type,
                          @Param("status") String status,
                          Pageable pageable);
}
