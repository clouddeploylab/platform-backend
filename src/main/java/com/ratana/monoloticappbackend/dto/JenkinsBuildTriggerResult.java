package com.ratana.monoloticappbackend.dto;

public record JenkinsBuildTriggerResult(
        String jobName,
        String queueUrl,
        Integer queueItemId
) {
}
