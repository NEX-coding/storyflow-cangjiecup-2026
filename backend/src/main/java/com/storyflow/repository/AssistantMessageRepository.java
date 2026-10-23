package com.storyflow.repository;

import com.storyflow.entity.AssistantMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, Long> {
    List<AssistantMessage> findByProjectIdOrderByCreatedAtAsc(Long projectId);
}
