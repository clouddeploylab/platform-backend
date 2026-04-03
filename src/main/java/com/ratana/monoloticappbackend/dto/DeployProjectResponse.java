package com.ratana.monoloticappbackend.dto;

import com.ratana.monoloticappbackend.model.Project;
import com.ratana.monoloticappbackend.model.ProjectRelease;

public record DeployProjectResponse(
        Project project,
        ProjectRelease release,
        String jenkinsJobName,
        String queueUrl,
        Integer queueItemId
) {
}
