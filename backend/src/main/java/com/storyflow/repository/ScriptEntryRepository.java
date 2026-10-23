package com.storyflow.repository;

import com.storyflow.entity.ScriptEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScriptEntryRepository extends JpaRepository<ScriptEntry, Long> {
    List<ScriptEntry> findByProjectIdOrderByOrderIndexAsc(Long projectId);
    void deleteByProjectId(Long projectId);
}
