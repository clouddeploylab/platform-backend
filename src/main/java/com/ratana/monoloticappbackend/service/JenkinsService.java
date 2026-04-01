package com.ratana.monoloticappbackend.service;

import com.ratana.monoloticappbackend.dto.JenkinsBuildTriggerResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

public interface JenkinsService {
    JenkinsBuildTriggerResult triggerBuild(
            String repoUrl,
            String branch,
            String appName,
            int appPort,
            String userId,
            String workspaceId,
            String customDomain
    );

    SseEmitter streamBuildLogs(String jobName, int buildNumber);

    void streamBuildLogsAsync(String jobName, int buildNumber, AtomicBoolean streamOpen, JenkinsLogStreamListener listener);

    void streamBuildLogsFromQueueAsync(String jobName, int queueItemId, AtomicBoolean streamOpen, JenkinsLogStreamListener listener);
}
