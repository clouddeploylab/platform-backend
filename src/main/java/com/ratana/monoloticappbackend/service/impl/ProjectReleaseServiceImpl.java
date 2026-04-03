package com.ratana.monoloticappbackend.service.impl;

import com.ratana.monoloticappbackend.model.Project;
import com.ratana.monoloticappbackend.model.ProjectRelease;
import com.ratana.monoloticappbackend.repository.ProjectReleaseRepository;
import com.ratana.monoloticappbackend.service.ProjectReleaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectReleaseServiceImpl implements ProjectReleaseService {

    private static final int MAX_RELEASE_HISTORY = 3;

    private final ProjectReleaseRepository releaseRepository;

    @Override
    @Transactional
    public ProjectRelease createReleaseSnapshot(
            Project project,
            String triggerType,
            String imageRepository,
            String imageTag,
            Integer appPort,
            String framework,
            String customDomain,
            String commitSha,
            Integer queueItemId
    ) {
        long nextVersionNumber = releaseRepository.findFirstByProjectIdOrderByVersionNumberDesc(project.getId())
                .map(ProjectRelease::getVersionNumber)
                .orElse(0L) + 1L;

        ProjectRelease release = new ProjectRelease();
        release.setProjectId(project.getId());
        release.setUserId(project.getUserId());
        release.setWorkspaceId(project.getWorkspaceId());
        release.setWorkspaceSlug(project.getWorkspaceSlug());
        release.setAppName(project.getAppName());
        release.setVersionNumber(nextVersionNumber);
        release.setVersionName(String.format("v1.0.%d", nextVersionNumber));
        release.setVersionTag(imageTag);
        release.setImageRepository(imageRepository);
        release.setBranch(project.getBranch());
        release.setFramework(framework);
        release.setAppPort(appPort);
        release.setCustomDomain(customDomain);
        release.setUrl(project.getUrl());
        release.setRepoUrl(project.getRepoUrl());
        release.setRepoFullName(project.getRepoFullName());
        release.setTriggerType(triggerType);
        release.setStatus("PENDING");
        release.setStatusMessage("Deployment requested");
        release.setCommitSha(commitSha);
        release.setQueueItemId(queueItemId);
        return saveAndTrimHistory(release);
    }

    @Override
    @Transactional
    public ProjectRelease createRollbackSnapshot(Project project, ProjectRelease sourceRelease, String imageTag) {
        long nextVersionNumber = releaseRepository.findFirstByProjectIdOrderByVersionNumberDesc(project.getId())
                .map(ProjectRelease::getVersionNumber)
                .orElse(0L) + 1L;

        ProjectRelease release = new ProjectRelease();
        release.setProjectId(project.getId());
        release.setUserId(project.getUserId());
        release.setWorkspaceId(project.getWorkspaceId());
        release.setWorkspaceSlug(project.getWorkspaceSlug());
        release.setAppName(project.getAppName());
        release.setVersionNumber(nextVersionNumber);
        release.setVersionName(String.format("v1.0.%d", nextVersionNumber));
        release.setVersionTag(imageTag);
        release.setImageRepository(sourceRelease.getImageRepository());
        release.setBranch(sourceRelease.getBranch());
        release.setFramework(sourceRelease.getFramework());
        release.setAppPort(sourceRelease.getAppPort());
        release.setCustomDomain(sourceRelease.getCustomDomain());
        release.setUrl(sourceRelease.getUrl());
        release.setRepoUrl(sourceRelease.getRepoUrl());
        release.setRepoFullName(sourceRelease.getRepoFullName());
        release.setTriggerType("ROLLBACK");
        release.setStatus("PENDING");
        release.setStatusMessage("Rollback requested");
        release.setRollbackFromReleaseId(sourceRelease.getId());
        release.setCommitSha(sourceRelease.getCommitSha());
        return saveAndTrimHistory(release);
    }

    @Override
    @Transactional
    public ProjectRelease markCompleted(String projectId, String releaseId, Integer buildNumber, String framework) {
        ProjectRelease release = getRelease(projectId, releaseId);
        release.setStatus("DEPLOYED");
        release.setStatusMessage("Deployment completed successfully");
        release.setBuildNumber(buildNumber);
        if (framework != null && !framework.isBlank()) {
            release.setFramework(framework.trim());
        }
        release.setAppPort(resolveRuntimePort(release.getFramework(), release.getAppPort()));
        release.setDeployedAt(Instant.now());
        ProjectRelease saved = releaseRepository.save(release);
        trimHistory(projectId);
        return saved;
    }

    @Override
    @Transactional
    public ProjectRelease markFailed(String projectId, String releaseId, Integer buildNumber, String framework, String statusMessage) {
        ProjectRelease release = getRelease(projectId, releaseId);
        release.setStatus("FAILED");
        release.setStatusMessage(statusMessage == null || statusMessage.isBlank()
                ? "Deployment failed"
                : statusMessage.trim());
        release.setBuildNumber(buildNumber);
        if (framework != null && !framework.isBlank()) {
            release.setFramework(framework.trim());
        }
        release.setAppPort(resolveRuntimePort(release.getFramework(), release.getAppPort()));
        ProjectRelease saved = releaseRepository.save(release);
        trimHistory(projectId);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectRelease> listRecentReleases(String projectId, int limit) {
        return releaseRepository.findByProjectIdOrderByVersionNumberDesc(projectId)
                .stream()
                .limit(Math.max(limit, 1))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectRelease getRelease(String projectId, String releaseId) {
        return releaseRepository.findByProjectIdAndId(projectId, releaseId)
                .orElseThrow(() -> new IllegalArgumentException("Release not found"));
    }

    private ProjectRelease saveAndTrimHistory(ProjectRelease release) {
        ProjectRelease saved = releaseRepository.save(release);
        trimHistory(saved.getProjectId());
        return saved;
    }

    private void trimHistory(String projectId) {
        List<ProjectRelease> releases = releaseRepository.findByProjectIdOrderByVersionNumberAsc(projectId);
        while (releases.size() > MAX_RELEASE_HISTORY) {
            ProjectRelease oldest = releases.remove(0);
            releaseRepository.delete(oldest);
        }
    }

    private Integer resolveRuntimePort(String framework, Integer currentPort) {
        String normalizedFramework = framework == null ? "" : framework.trim().toLowerCase();
        if (normalizedFramework.isBlank()) {
          return currentPort;
        }

        return switch (normalizedFramework) {
            case "nextjs", "nodejs" -> currentPort == null || currentPort == 3000 ? 3000 : currentPort;
            case "react", "laravel", "php", "static" -> currentPort == null || currentPort == 3000 ? 80 : currentPort;
            case "springboot-maven", "springboot-gradle", "java-maven", "java-gradle" -> 8080;
            case "fastapi", "flask", "python" -> 8000;
            default -> currentPort == null ? 3000 : currentPort;
        };
    }
}
