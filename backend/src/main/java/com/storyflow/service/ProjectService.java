package com.storyflow.service;

import com.storyflow.ai.ProjectSettingsSupport;
import com.storyflow.dto.ProjectDtos;
import com.storyflow.entity.AssetType;
import com.storyflow.entity.Project;
import com.storyflow.entity.SourceText;
import com.storyflow.repository.ProjectRepository;
import com.storyflow.repository.ScriptEntryRepository;
import com.storyflow.repository.ShotRepository;
import com.storyflow.repository.SourceTextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final SourceTextRepository sourceTextRepository;
    private final ScriptEntryRepository scriptEntryRepository;
    private final ShotRepository shotRepository;
    private final CurrentUserService currentUserService;
    private final DemoDataService demoDataService;
    private final ProjectAccessService projectAccessService;
    private final QuotaService quotaService;
    private final AnonymousSessionService anonymousSessionService;
    private final ProjectSettingsSupport projectSettingsSupport;

    public ProjectService(ProjectRepository projectRepository, SourceTextRepository sourceTextRepository,
                          ScriptEntryRepository scriptEntryRepository, ShotRepository shotRepository,
                          CurrentUserService currentUserService, DemoDataService demoDataService,
                          ProjectAccessService projectAccessService, QuotaService quotaService,
                          AnonymousSessionService anonymousSessionService,
                          ProjectSettingsSupport projectSettingsSupport) {
        this.projectRepository = projectRepository;
        this.sourceTextRepository = sourceTextRepository;
        this.scriptEntryRepository = scriptEntryRepository;
        this.shotRepository = shotRepository;
        this.currentUserService = currentUserService;
        this.demoDataService = demoDataService;
        this.projectAccessService = projectAccessService;
        this.quotaService = quotaService;
        this.anonymousSessionService = anonymousSessionService;
        this.projectSettingsSupport = projectSettingsSupport;
    }

    public List<ProjectDtos.ProjectResponse> list() {
        Long userId = currentUserService.getCurrentUserId();
        if (currentUserService.isGuest()) {
            String sessionId = anonymousSessionService.getCurrentSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                return List.of();
            }
            return projectRepository.findByUserIdAndAnonymousSessionIdOrderByUpdatedAtDesc(userId, sessionId).stream()
                    .map(this::toResponse)
                    .toList();
        }
        return projectRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ProjectDtos.ProjectResponse create(ProjectDtos.CreateRequest request) {
        quotaService.checkProjectCreate();
        Project project = new Project();
        project.setUserId(currentUserService.getCurrentUserId());
        if (currentUserService.isGuest()) {
            project.setAnonymousSessionId(anonymousSessionService.getCurrentSessionId());
        }
        project.setTitle(request.getTitle());
        if (request.getStylePreset() != null) {
            project.setStylePreset(request.getStylePreset());
        }
        projectRepository.save(project);

        SourceText sourceText = new SourceText();
        sourceText.setProjectId(project.getId());
        sourceText.setContent("");
        sourceTextRepository.save(sourceText);

        return toResponse(project);
    }

    public ProjectDtos.ProjectResponse get(Long projectId) {
        return toResponse(getProject(projectId));
    }

    @Transactional
    public ProjectDtos.ProjectResponse update(Long projectId, ProjectDtos.UpdateRequest request) {
        Project project = requireWritableProject(projectId);
        if (request.getTitle() != null) {
            project.setTitle(request.getTitle());
        }
        if (request.getStylePreset() != null) {
            project.setStylePreset(request.getStylePreset());
        }
        if (request.getElementStyles() != null
                || request.getRefPromptMode() != null
                || request.getVideoAspectRatio() != null
                || request.getRefImageAspectRatio() != null
                || request.getAssetImageAspectRatio() != null
                || request.getStyleReferenceNote() != null) {
            ProjectSettingsSupport.Settings current = projectSettingsSupport.read(project);
            ProjectSettingsSupport.ElementStyle character = toStyle(
                    request.getElementStyles() != null ? request.getElementStyles().getCharacter() : null,
                    current.character());
            ProjectSettingsSupport.ElementStyle scene = toStyle(
                    request.getElementStyles() != null ? request.getElementStyles().getScene() : null,
                    current.scene());
            ProjectSettingsSupport.ElementStyle prop = toStyle(
                    request.getElementStyles() != null ? request.getElementStyles().getProp() : null,
                    current.prop());
            String mode = request.getRefPromptMode() != null && !request.getRefPromptMode().isBlank()
                    ? request.getRefPromptMode().trim()
                    : current.refPromptMode();
            String videoRatio = request.getVideoAspectRatio() != null && !request.getVideoAspectRatio().isBlank()
                    ? request.getVideoAspectRatio().trim()
                    : current.videoAspectRatio();
            String refImageRatio = request.getRefImageAspectRatio() != null && !request.getRefImageAspectRatio().isBlank()
                    ? request.getRefImageAspectRatio().trim()
                    : current.refImageAspectRatio();
            String assetImageRatio = request.getAssetImageAspectRatio() != null
                    && !request.getAssetImageAspectRatio().isBlank()
                    ? request.getAssetImageAspectRatio().trim()
                    : current.assetImageAspectRatio();
            String styleRefNote = request.getStyleReferenceNote() != null
                    ? request.getStyleReferenceNote().trim()
                    : current.styleReferenceNote();
            project.setSettingsJson(projectSettingsSupport.mergeInto(
                    project.getSettingsJson(),
                    new ProjectSettingsSupport.Settings(
                            character, scene, prop, mode, videoRatio, refImageRatio, assetImageRatio,
                            styleRefNote)));
        }
        projectRepository.save(project);
        return toResponse(project);
    }

    @Transactional
    public void delete(Long projectId) {
        projectRepository.delete(requireWritableProject(projectId));
    }

    @Transactional
    public ProjectDtos.ProjectResponse createDemo() {
        quotaService.checkProjectCreate();
        return demoDataService.createDemoProject();
    }

    public Project getProject(Long projectId) {
        return projectAccessService.requireRead(projectId);
    }

    public Project requireWritableProject(Long projectId) {
        return projectAccessService.requireWrite(projectId);
    }

    private ProjectDtos.ProjectResponse toResponse(Project project) {
        ProjectDtos.ProjectResponse resp = new ProjectDtos.ProjectResponse();
        resp.setId(project.getId());
        resp.setTitle(project.getTitle());
        resp.setStylePreset(project.getStylePreset());
        ProjectSettingsSupport.Settings settings = projectSettingsSupport.read(project);
        resp.setElementStyles(toStylesDto(settings));
        resp.setRefPromptMode(settings.refPromptMode());
        resp.setVideoAspectRatio(settings.normalizedVideoAspectRatio());
        resp.setRefImageAspectRatio(settings.normalizedRefImageAspectRatio());
        resp.setAssetImageAspectRatio(settings.normalizedAssetImageAspectRatio());
        resp.setStyleReferenceNote(settings.styleReferenceNote());
        resp.setCreatedAt(project.getCreatedAt());
        resp.setUpdatedAt(project.getUpdatedAt());
        resp.setScriptCount(scriptEntryRepository.findByProjectIdOrderByOrderIndexAsc(project.getId()).size());
        resp.setShotCount(shotRepository.findByProjectIdWithRefs(project.getId()).size());
        resp.setReadOnly(projectAccessService.isReadOnlyForCurrentUser(project));
        return resp;
    }

    private static ProjectSettingsSupport.ElementStyle toStyle(
            ProjectDtos.ElementStyleDto dto, ProjectSettingsSupport.ElementStyle fallback) {
        if (dto == null) {
            return fallback != null ? fallback : ProjectSettingsSupport.ElementStyle.empty();
        }
        return new ProjectSettingsSupport.ElementStyle(
                dto.getPrefix() != null ? dto.getPrefix() : "",
                dto.getSuffix() != null ? dto.getSuffix() : "");
    }

    private ProjectDtos.ElementStylesDto toStylesDto(ProjectSettingsSupport.Settings settings) {
        ProjectDtos.ElementStylesDto dto = new ProjectDtos.ElementStylesDto();
        dto.setCharacter(toStyleDto(settings.character(), AssetType.character));
        dto.setScene(toStyleDto(settings.scene(), AssetType.scene));
        dto.setProp(toStyleDto(settings.prop(), AssetType.prop));
        return dto;
    }

    private ProjectDtos.ElementStyleDto toStyleDto(
            ProjectSettingsSupport.ElementStyle style, AssetType type) {
        ProjectSettingsSupport.ElementStyle s = style != null ? style : projectSettingsSupport.defaultStyle(type);
        ProjectDtos.ElementStyleDto dto = new ProjectDtos.ElementStyleDto();
        dto.setPrefix(s.prefix());
        dto.setSuffix(s.suffix());
        return dto;
    }
}
