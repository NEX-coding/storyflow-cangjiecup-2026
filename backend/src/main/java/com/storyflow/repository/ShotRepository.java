package com.storyflow.repository;

import com.storyflow.entity.Shot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ShotRepository extends JpaRepository<Shot, Long> {

    @Query("SELECT s FROM Shot s LEFT JOIN FETCH s.referencePrompts WHERE s.projectId = :projectId ORDER BY s.orderIndex ASC")
    List<Shot> findByProjectIdWithRefs(Long projectId);

    Optional<Shot> findByIdAndProjectId(Long id, Long projectId);

    @Query("SELECT s FROM Shot s LEFT JOIN FETCH s.referencePrompts WHERE s.id = :id AND s.projectId = :projectId")
    Optional<Shot> findByIdAndProjectIdWithRefs(Long id, Long projectId);

    void deleteByProjectId(Long projectId);
}
