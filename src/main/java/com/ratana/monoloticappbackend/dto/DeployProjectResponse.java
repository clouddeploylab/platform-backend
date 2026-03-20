package com.ratana.monoloticappbackend.dto;

import com.ratana.monoloticappbackend.model.Project;

public record DeployProjectResponse(
        Project project,
        String jenkinsJobName,
        String queueUrl,
        Integer queueItemId
) {
}
