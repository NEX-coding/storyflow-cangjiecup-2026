package com.storyflow.repository;

import com.storyflow.entity.ShotVideo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShotVideoRepository extends JpaRepository<ShotVideo, Long> {

    List<ShotVideo> findByStatus(String status);

    List<ShotVideo> findByProjectIdAndShotIdOrderByCreatedAtDesc(Long projectId, Long shotId);

    Optional<ShotVideo> findByIdAndProjectIdAndShotId(Long id, Long projectId, Long shotId);
}
