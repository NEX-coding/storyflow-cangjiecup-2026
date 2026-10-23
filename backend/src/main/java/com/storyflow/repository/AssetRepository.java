package com.storyflow.repository;

import com.storyflow.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, Long> {
    List<Asset> findByProjectIdOrderByTypeAscNameAsc(Long projectId);
    Optional<Asset> findByIdAndProjectId(Long id, Long projectId);
    void deleteByProjectId(Long projectId);
}
