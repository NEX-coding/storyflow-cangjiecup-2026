package com.storyflow.service;

import com.storyflow.common.BusinessException;
import com.storyflow.entity.Project;
import com.storyflow.repository.ProjectRepository;
import org.springframework.stereotype.Service;

@Service
public class ProjectAccessService {

    private final ProjectRepository projectRepository;
    private final CurrentUserService currentUserService;
    private final AnonymousSessionService anonymousSessionService;

    public ProjectAccessService(ProjectRepository projectRepository,
                                CurrentUserService currentUserService,
                                AnonymousSessionService anonymousSessionService) {
        this.projectRepository = projectRepository;
        this.currentUserService = currentUserService;
        this.anonymousSessionService = anonymousSessionService;
    }

    public Project requireRead(Long projectId) {
        Project project = findProject(projectId);
        if (!canRead(project)) {
            throw new BusinessException(403, "无权访问该项目");
        }
        return project;
    }

    public Project requireWrite(Long projectId) {
        Project project = requireRead(projectId);
        if (!canWrite(project)) {
            throw new BusinessException(403, "无权编辑该项目");
        }
        return project;
    }

    public boolean canRead(Project project) {
        Long ownerId = project.getUserId();
        Long currentId = currentUserService.getCurrentUserId();
        if (ownerId == null || !ownerId.equals(currentId)) {
            return false;
        }
        if (currentUserService.isGuest()) {
            return ownsGuestProject(project);
        }
        return true;
    }

    public boolean canWrite(Project project) {
        if (currentUserService.isGuest()) {
            return ownsGuestProject(project);
        }
        if (currentUserService.isAuthenticated()) {
            Long currentId = currentUserService.getCurrentUserId();
            return currentId.equals(project.getUserId());
        }
        return false;
    }

    public boolean isGuestProject(Project project) {
        Long guestUserId = currentUserService.getGuestUserId().orElse(null);
        return guestUserId != null && guestUserId.equals(project.getUserId());
    }

    public boolean ownsGuestProject(Project project) {
        return isGuestProject(project) && anonymousSessionService.matchesProjectSession(project.getAnonymousSessionId());
    }

    public boolean isReadOnlyForCurrentUser(Project project) {
        return canRead(project) && !canWrite(project);
    }

    private Project findProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(404, "项目不存在"));
    }
}
