package com.storyflow.repository;

import com.storyflow.entity.ProjectSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectSnapshotRepository extends JpaRepository<ProjectSnapshot, Long> {
    List<ProjectSnapshot> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    Optional<ProjectSnapshot> findByIdAndProjectId(Long id, Long projectId);

    void deleteByProjectId(Long projectId);
}
