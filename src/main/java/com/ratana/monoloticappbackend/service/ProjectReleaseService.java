package com.ratana.monoloticappbackend.service;

import com.ratana.monoloticappbackend.model.Project;
import com.ratana.monoloticappbackend.model.ProjectRelease;

import java.util.List;

public interface ProjectReleaseService {
    ProjectRelease createReleaseSnapshot(
            Project project,
            String triggerType,
            String imageRepository,
            String imageTag,
            Integer appPort,
            String framework,
            String customDomain,
            String commitSha,
            Integer queueItemId
    );

    ProjectRelease createRollbackSnapshot(Project project, ProjectRelease sourceRelease, String imageTag);

    ProjectRelease markCompleted(String projectId, String releaseId, Integer buildNumber, String framework);

    ProjectRelease markFailed(String projectId, String releaseId, Integer buildNumber, String framework, String statusMessage);

    List<ProjectRelease> listRecentReleases(String projectId, int limit);

    ProjectRelease getRelease(String projectId, String releaseId);
}
