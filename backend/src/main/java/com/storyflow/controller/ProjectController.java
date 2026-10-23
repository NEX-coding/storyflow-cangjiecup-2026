package com.storyflow.controller;

import com.storyflow.common.ApiResponse;
import com.storyflow.dto.ProjectDtos;
import com.storyflow.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public ApiResponse<List<ProjectDtos.ProjectResponse>> list() {
        return ApiResponse.ok(projectService.list());
    }

    @PostMapping
    public ApiResponse<ProjectDtos.ProjectResponse> create(@Valid @RequestBody ProjectDtos.CreateRequest request) {
        return ApiResponse.ok(projectService.create(request));
    }

    @PostMapping("/demo")
    public ApiResponse<ProjectDtos.ProjectResponse> createDemo() {
        return ApiResponse.ok(projectService.createDemo());
    }

    @GetMapping("/{id}")
    public ApiResponse<ProjectDtos.ProjectResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(projectService.get(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProjectDtos.ProjectResponse> update(@PathVariable Long id,
                                                           @RequestBody ProjectDtos.UpdateRequest request) {
        return ApiResponse.ok(projectService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        projectService.delete(id);
        return ApiResponse.ok("删除成功", null);
    }
}
