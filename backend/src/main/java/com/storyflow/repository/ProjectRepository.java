package com.storyflow.repository;

import com.storyflow.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findAllByOrderByUpdatedAtDesc();
    List<Project> findByUserIdOrderByUpdatedAtDesc(Long userId);

    List<Project> findByUserIdAndAnonymousSessionIdOrderByUpdatedAtDesc(Long userId, String anonymousSessionId);

    Optional<Project> findByIdAndUserId(Long id, Long userId);
    boolean existsByTitle(String title);
    long countByUserId(Long userId);

    long countByUserIdAndAnonymousSessionId(Long userId, String anonymousSessionId);
}
