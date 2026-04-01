package com.ratana.monoloticappbackend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratana.monoloticappbackend.dto.JenkinsBuildTriggerResult;
import com.ratana.monoloticappbackend.service.JenkinsLogStreamListener;
import com.ratana.monoloticappbackend.service.JenkinsService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.UriUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class JenkinsServiceImpl implements JenkinsService {

    @Value("${jenkins.url}")
    private String jenkinsUrl;

    @Value("${jenkins.token}")
    private String jenkinsToken;

    @Value("${jenkins.user}")
    private String jenkinsUser;

    @Value("${jenkins.job-name:deploy-pipeline}")
    private String defaultJobName;

    @Value("${jenkins.log-poll-interval-ms:1000}")
    private long logPollIntervalMs;

    @Value("${jenkins.log-heartbeat-interval-ms:15000}")
    private long heartbeatIntervalMs;

    @Value("${jenkins.queue-poll-interval-ms:2000}")
    private long queuePollIntervalMs;

    @Value("${jenkins.queue-poll-timeout-ms:180000}")
    private long queuePollTimeoutMs;

    @Value("${platform.domain:apps.example.com}")
    private String platformDomain;

    @Value("${gitops.branch:main}")
    private String gitopsBranch;

    @Value("${jenkins.enable-gitops-update:true}")
    private boolean enableGitopsUpdate;

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService logStreamExecutor = Executors.newCachedThreadPool();
    private static final Pattern QUEUE_ITEM_PATTERN = Pattern.compile("/queue/item/(\\d+)/?");

    @Override
    public JenkinsBuildTriggerResult triggerBuild(
            String repoUrl,
            String branch,
            String appName,
            int appPort,
            String userId,
            String workspaceId,
            String customDomain
    ) {
        String url = String.format("%s/%s/buildWithParameters", trimTrailingSlash(jenkinsUrl), buildPath(defaultJobName));

        HttpHeaders headers = basicAuthHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        maybeAddCrumbHeader(headers);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        String enableGitopsValue = String.valueOf(enableGitopsUpdate);
        form.add("REPO_URL", normalizeRepoUrl(repoUrl));
        form.add("BRANCH", branch);
        form.add("APP_NAME", appName);
        form.add("PROJECT_NAME", appName);
        form.add("APP_PORT", String.valueOf(appPort));
        form.add("USER_ID", userId);
        form.add("WORKSPACE_ID", workspaceId == null ? "" : workspaceId.trim());
        form.add("CUSTOM_DOMAIN", normalizeCustomDomain(customDomain));
        form.add("PLATFORM_DOMAIN", platformDomain);
        form.add("GITOPS_BRANCH", gitopsBranch);
        form.add("ENABLE_GITOPS_UPDATE", enableGitopsValue);

        ResponseEntity<String> response;
        try {
            response = rest.postForEntity(url, new HttpEntity<>(form, headers), String.class);
        } catch (HttpStatusCodeException httpError) {
            log.error("Jenkins trigger failed status={} body={}", httpError.getRawStatusCode(), httpError.getResponseBodyAsString(), httpError);
            throw httpError;
        } catch (ResourceAccessException networkError) {
            log.error("Unable to reach Jenkins at {}", jenkinsUrl, networkError);
            throw networkError;
        }

        log.info("Triggered Jenkins deploy-pipeline for app '{}' user '{}' workspaceId='{}' branch='{}' customDomain='{}' gitopsBranch='{}' enableGitopsUpdate={} -> status={} queueLocation={}",
                appName,
                userId,
                workspaceId,
                branch,
                normalizeCustomDomain(customDomain),
                gitopsBranch,
                enableGitopsValue,
                response.getStatusCode().value(),
                response.getHeaders().getFirst("Location"));
        String queueUrl = response.getHeaders().getFirst("Location");
        Integer queueItemId = parseQueueItemId(queueUrl);
        return new JenkinsBuildTriggerResult(defaultJobName, queueUrl, queueItemId);
    }

    @Override
    public SseEmitter streamBuildLogs(String jobName, int buildNumber) {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean streamOpen = new AtomicBoolean(true);

        emitter.onCompletion(() -> streamOpen.set(false));
        emitter.onTimeout(() -> {
            streamOpen.set(false);
            emitter.complete();
        });
        emitter.onError(error -> streamOpen.set(false));

        streamBuildLogsAsync(jobName, buildNumber, streamOpen, new JenkinsLogStreamListener() {
            @Override
            public void onOpen(String resolvedJobName, int resolvedBuildNumber) throws Exception {
                emitter.send(SseEmitter.event().name("open").data(Map.of("job", resolvedJobName, "build", resolvedBuildNumber)));
            }

            @Override
            public void onLog(String chunk, long nextOffset, int resolvedBuildNumber) throws Exception {
                emitter.send(SseEmitter.event().name("log").data(Map.of(
                        "chunk", chunk,
                        "offset", nextOffset,
                        "build", resolvedBuildNumber
                )));
            }

            @Override
            public void onHeartbeat(String timestamp) throws Exception {
                emitter.send(SseEmitter.event().name("heartbeat").data(Map.of("timestamp", timestamp)));
            }

            @Override
            public void onDone(int resolvedBuildNumber) throws Exception {
                emitter.send(SseEmitter.event().name("done").data(Map.of(
                        "message", "Log stream completed",
                        "build", resolvedBuildNumber
                )));
                emitter.complete();
            }

            @Override
            public void onError(String message, String detail) throws Exception {
                emitter.send(SseEmitter.event().name("error").data(Map.of(
                        "message", message,
                        "detail", detail
                )));
                emitter.completeWithError(new IllegalStateException(detail));
            }
        });
        return emitter;
    }

    @Override
    public void streamBuildLogsAsync(String jobName, int buildNumber, AtomicBoolean streamOpen, JenkinsLogStreamListener listener) {
        String resolvedJobName = resolveJobName(jobName);
        logStreamExecutor.execute(() -> streamProgressiveLogs(resolvedJobName, buildNumber, streamOpen, listener));
    }

    @Override
    public void streamBuildLogsFromQueueAsync(String jobName, int queueItemId, AtomicBoolean streamOpen, JenkinsLogStreamListener listener) {
        String resolvedJobName = resolveJobName(jobName);
        logStreamExecutor.execute(() -> {
            try {
                int buildNumber = waitForExecutableBuildFromQueue(queueItemId, streamOpen, listener);
                if (buildNumber <= 0 || !streamOpen.get()) {
                    return;
                }
                streamProgressiveLogs(resolvedJobName, buildNumber, streamOpen, listener);
            } catch (Exception ex) {
                log.error("Failed to resolve Jenkins queue item '{}' for job '{}'", queueItemId, resolvedJobName, ex);
                if (streamOpen.get()) {
                    try {
                        String detail = ex.getMessage() == null ? "Unknown error" : ex.getMessage();
                        listener.onError("Failed to resolve Jenkins build queue item", detail);
                    } catch (Exception ignored) {
                        // Ignore secondary listener error and close stream.
                    }
                    streamOpen.set(false);
                }
            }
        });
    }

    private String normalizeRepoUrl(String repoUrl) {
        String value = repoUrl == null ? "" : repoUrl.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http%3a") || lower.startsWith("https%3a") || lower.startsWith("git%40")) {
            try {
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                log.warn("Failed to decode repo URL '{}', using original value", value);
            }
        }
        return value;
    }

    private String normalizeCustomDomain(String customDomain) {
        if (customDomain == null) {
            return "";
        }
        String value = customDomain.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return "";
        }
        value = value.replaceFirst("^https?://", "");
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        return value.trim();
    }

    private Integer parseQueueItemId(String queueUrl) {
        if (queueUrl == null || queueUrl.isBlank()) {
            return null;
        }
        Matcher matcher = QUEUE_ITEM_PATTERN.matcher(queueUrl);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private void streamProgressiveLogs(String jobName, int buildNumber, AtomicBoolean streamOpen, JenkinsLogStreamListener listener) {
        long start = 0L;
        long lastHeartbeatAt = System.currentTimeMillis();

        try {
            listener.onOpen(jobName, buildNumber);

            while (streamOpen.get()) {
                ProgressiveLogChunk chunk = fetchProgressiveChunk(jobName, buildNumber, start);
                if (!chunk.text().isEmpty()) {
                    listener.onLog(chunk.text(), chunk.nextStart(), buildNumber);
                }

                start = chunk.nextStart();

                long now = System.currentTimeMillis();
                if (now - lastHeartbeatAt >= heartbeatIntervalMs) {
                    listener.onHeartbeat(Instant.now().toString());
                    lastHeartbeatAt = now;
                }

                if (!chunk.moreData()) {
                    listener.onDone(buildNumber);
                    streamOpen.set(false);
                    return;
                }

                Thread.sleep(Math.max(logPollIntervalMs, 250));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.error("Failed to stream Jenkins logs for job '{}' build '{}'", jobName, buildNumber, ex);
            if (streamOpen.get()) {
                try {
                    String detail = ex.getMessage() == null ? "Unknown error" : ex.getMessage();
                    listener.onError("Failed to read logs from Jenkins", detail);
                } catch (Exception ignored) {
                    // Ignore secondary listener error and close stream.
                }
                streamOpen.set(false);
            }
        }
    }

    private int waitForExecutableBuildFromQueue(int queueItemId, AtomicBoolean streamOpen, JenkinsLogStreamListener listener) throws Exception {
        long startedAt = System.currentTimeMillis();
        String lastWhy = "";
        listener.onQueued(queueItemId, "Queued in Jenkins. Waiting for build number...");

        while (streamOpen.get()) {
            QueueStatus queueStatus = fetchQueueStatus(queueItemId);

            if (queueStatus.cancelled()) {
                throw new IllegalStateException("Jenkins queue item was cancelled");
            }

            if (queueStatus.buildNumber() != null && queueStatus.buildNumber() > 0) {
                return queueStatus.buildNumber();
            }

            String why = queueStatus.why() == null ? "" : queueStatus.why().trim();
            if (!why.isEmpty() && !why.equals(lastWhy)) {
                listener.onQueued(queueItemId, why);
                lastWhy = why;
            }

            if (System.currentTimeMillis() - startedAt > queuePollTimeoutMs) {
                throw new IllegalStateException("Timed out waiting for Jenkins to start build");
            }

            Thread.sleep(Math.max(queuePollIntervalMs, 500));
        }

        return -1;
    }

    private QueueStatus fetchQueueStatus(int queueItemId) throws Exception {
        String queueUrl = String.format("%s/queue/item/%d/api/json?tree=cancelled,why,executable[number]",
                trimTrailingSlash(jenkinsUrl),
                queueItemId
        );

        HttpHeaders headers = basicAuthHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = rest.exchange(
                queueUrl,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        JsonNode root = objectMapper.readTree(response.getBody() == null ? "{}" : response.getBody());
        boolean cancelled = root.path("cancelled").asBoolean(false);
        String why = root.path("why").asText("");
        JsonNode executable = root.path("executable");
        Integer buildNumber = null;
        if (!executable.isMissingNode() && executable.has("number")) {
            int parsed = executable.path("number").asInt(-1);
            if (parsed > 0) {
                buildNumber = parsed;
            }
        }
        return new QueueStatus(buildNumber, cancelled, why);
    }

    private ProgressiveLogChunk fetchProgressiveChunk(String jobName, int buildNumber, long start) {
        String progressiveLogUrl = String.format(
                "%s/%s/%d/logText/progressiveText?start=%d",
                trimTrailingSlash(jenkinsUrl),
                buildPath(jobName),
                buildNumber,
                start
        );

        HttpHeaders headers = basicAuthHeaders();
        headers.setAccept(List.of(MediaType.TEXT_PLAIN));

        ResponseEntity<String> response = rest.exchange(
                progressiveLogUrl,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        String body = response.getBody() == null ? "" : response.getBody();
        long nextStart = parseLong(response.getHeaders().getFirst("X-Text-Size"), start + body.length());
        boolean moreData = Boolean.parseBoolean(response.getHeaders().getFirst("X-More-Data"));

        return new ProgressiveLogChunk(body, nextStart, moreData);
    }

    private HttpHeaders basicAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        String auth = Base64.getEncoder().encodeToString((jenkinsUser + ":" + jenkinsToken).getBytes(StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + auth);
        return headers;
    }

    private void maybeAddCrumbHeader(HttpHeaders headers) {
        String crumbUrl = String.format("%s/crumbIssuer/api/json", trimTrailingSlash(jenkinsUrl));
        HttpHeaders crumbHeaders = basicAuthHeaders();
        crumbHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));

        try {
            ResponseEntity<String> response = rest.exchange(
                    crumbUrl,
                    HttpMethod.GET,
                    new HttpEntity<>(crumbHeaders),
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody() == null ? "{}" : response.getBody());
            String crumbRequestField = root.path("crumbRequestField").asText("");
            String crumb = root.path("crumb").asText("");
            if (!crumbRequestField.isBlank() && !crumb.isBlank()) {
                headers.set(crumbRequestField, crumb);
            }
        } catch (Exception ex) {
            // Some Jenkins setups do not require crumbs for API-token auth.
            log.debug("Jenkins crumb was not added (continuing without it): {}", ex.getMessage());
        }
    }

    private long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String resolveJobName(String jobName) {
        if (jobName == null || jobName.isBlank()) {
            return defaultJobName;
        }
        return jobName.trim();
    }

    private String buildPath(String jobName) {
        return Arrays.stream(resolveJobName(jobName).split("/"))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .map(part -> "job/" + UriUtils.encodePathSegment(part, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));
    }

    @PreDestroy
    void shutdown() {
        logStreamExecutor.shutdownNow();
    }

    private record ProgressiveLogChunk(String text, long nextStart, boolean moreData) {
    }

    private record QueueStatus(Integer buildNumber, boolean cancelled, String why) {
    }
}
