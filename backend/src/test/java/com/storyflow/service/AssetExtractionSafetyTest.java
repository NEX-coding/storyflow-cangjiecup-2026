package com.storyflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyflow.ai.*;
import com.storyflow.common.BusinessException;
import com.storyflow.entity.*;
import com.storyflow.repository.*;
import com.storyflow.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssetExtractionSafetyTest {
    @Test
    void laterModelFailureDoesNotClearOrPartiallyReplaceExistingAssets() {
        ProjectService projects = mock(ProjectService.class);
        SourceTextRepository sources = mock(SourceTextRepository.class);
        ScriptEntryRepository scripts = mock(ScriptEntryRepository.class);
        AssetRepository assets = mock(AssetRepository.class);
        LlmGateway gateway = mock(LlmGateway.class);
        Project project = new Project();
        project.setStylePreset("电影胶片");
        when(projects.requireWritableProject(5L)).thenReturn(project);
        when(sources.findByProjectId(5L)).thenReturn(Optional.empty());
        when(scripts.findByProjectIdOrderByOrderIndexAsc(5L)).thenReturn(List.of(new ScriptEntry()));
        when(gateway.extractAssetsOfType(eq("character"), anyString(), anyString(), anyString()))
                .thenReturn(List.of(new LlmGateway.AssetDto("character", "林夏", "大学生", "人物参考图")));
        when(gateway.extractAssetsOfType(eq("scene"), anyString(), anyString(), anyString()))
                .thenThrow(new BusinessException("AI 返回结构缺少 items 数组"));
        WorkspaceService service = new WorkspaceService(projects, sources, scripts, assets,
                mock(ShotRepository.class), mock(ProjectSnapshotRepository.class), gateway,
                mock(PromptComposeSupport.class), mock(ProjectSettingsSupport.class),
                new JsonUtil(new ObjectMapper()), mock(AiUsageLogService.class), mock(QuotaService.class),
                mock(MediaVaultService.class), mock(PlatformTransactionManager.class));
        assertThrows(BusinessException.class, () -> service.extractAssets(5L));
        verify(assets, never()).deleteByProjectId(anyLong());
        verify(assets, never()).save(any(Asset.class));
    }
}
