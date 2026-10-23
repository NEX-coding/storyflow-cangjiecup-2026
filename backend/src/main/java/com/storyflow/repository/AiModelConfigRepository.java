package com.storyflow.repository;

import com.storyflow.entity.AiModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiModelConfigRepository extends JpaRepository<AiModelConfig, Long> {
    Optional<AiModelConfig> findFirstByActiveTrueOrderByIdDesc();
}
