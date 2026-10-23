package com.storyflow.repository;

import com.storyflow.entity.PromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, Long> {
    Optional<PromptTemplate> findFirstByCategoryAndActiveTrueOrderByVersionDesc(String category);
    List<PromptTemplate> findAllByOrderByCategoryAscVersionDesc();
}
