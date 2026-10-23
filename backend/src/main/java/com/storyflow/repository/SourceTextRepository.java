package com.storyflow.repository;

import com.storyflow.entity.SourceText;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SourceTextRepository extends JpaRepository<SourceText, Long> {
    Optional<SourceText> findByProjectId(Long projectId);
}
