package com.ratana.monoloticappbackend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratana.monoloticappbackend.dto.JenkinsBuildTriggerResult;
import com.ratana.monoloticappbackend.model.Project;
import com.ratana.monoloticappbackend.repository.ProjectRepository;
import com.ratana.monoloticappbackend.service.JenkinsService;
import com.ratana.monoloticappbackend.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private final ProjectRepository projectRepo;
    private final JenkinsService jenkinsService;
    private final WebhookService webhookService;
    private final ObjectMapper objectMapper;

    @PostMapping("/github")
    public ResponseEntity<?> githubWebhook(
            @RequestHeader(value = "X-GitHub-Event", required = false) String githubEvent,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signatureHeader,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestBody String rawPayload
    ) {
        try {
            String event = githubEvent == null ? "" : githubEvent.trim();
            if (!"push".equalsIgnoreCase(githubEvent)) {
                log.info("GitHub webhook ignored deliveryId={} reason=non-push-event event={}", deliveryId, event);
                return ResponseEntity.ok(Map.of(
                        "status", "ignored",
                        "reason", "non-push-event",
                        "event", event
                ));
            }

            String payloadJson = extractPayloadJson(rawPayload, contentType);
            JsonNode payload = objectMapper.readTree(payloadJson);
            String repoFullName = payload.path("repository").path("full_name").asText("");
            String ref = payload.path("ref").asText("");
            String branch = ref.replace("refs/heads/", "").trim();

            if (repoFullName.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing repository.full_name"));
            }

            List<Project> allProjects = projectRepo.findAllByRepoProviderIgnoreCaseAndRepoFullNameIgnoreCase("github", repoFullName);
            if (allProjects.isEmpty()) {
                log.info("GitHub webhook ignored deliveryId={} reason=project-not-found repo={} branch={} ref={}",
                        deliveryId, repoFullName, branch, ref);
                return ResponseEntity.ok(Map.of(
                        "status", "ignored",
                        "reason", "project-not-found",
                        "repoFullName", repoFullName,
                        "branch", branch
                ));
            }

            List<Project> branchProjects = allProjects.stream()
                    .filter(project -> {
                        String configured = project.getBranch() == null ? "" : project.getBranch().trim();
                        return configured.equals(branch);
                    })
                    .toList();
            if (branchProjects.isEmpty()) {
                log.info("GitHub webhook ignored deliveryId={} projectId={} reason=branch-mismatch repo={} branch={}",
                        deliveryId, "N/A", repoFullName, branch);
                return ResponseEntity.ok(Map.of(
                        "status", "ignored",
                        "reason", "branch-mismatch",
                        "repoFullName", repoFullName,
                        "receivedBranch", branch
                ));
            }

            List<Project> autoDeployProjects = branchProjects.stream()
                    .filter(project -> Boolean.TRUE.equals(project.getAutoDeployEnabled()))
                    .toList();
            if (autoDeployProjects.isEmpty()) {
                String projectId = branchProjects.get(0).getId();
                log.info("GitHub webhook ignored deliveryId={} projectId={} reason=auto-deploy-disabled repo={} branch={}",
                        deliveryId, projectId, repoFullName, branch);
                return ResponseEntity.ok(Map.of(
                        "status", "ignored",
                        "reason", "auto-deploy-disabled",
                        "projectId", projectId
                ));
            }

            Project project = null;
            boolean hasMissingSecret = false;
            for (Project candidate : autoDeployProjects) {
                if (candidate.getWebhookSecret() == null || candidate.getWebhookSecret().isBlank()) {
                    hasMissingSecret = true;
                    continue;
                }
                try {
                    String decryptedSecret = webhookService.decryptSecret(candidate.getWebhookSecret());
                    boolean valid = webhookService.verifyGithubSignature(decryptedSecret, rawPayload, signatureHeader);
                    if (valid) {
                        project = candidate;
                        break;
                    }
                } catch (Exception ex) {
                    log.warn("Failed to validate webhook signature for candidate projectId={}", candidate.getId(), ex);
                }
            }

            if (project == null && hasMissingSecret) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Webhook secret not configured"));
            }

            if (project == null) {
                log.warn("GitHub webhook rejected deliveryId={} projectId={} reason=invalid-signature repo={} branch={}",
                        deliveryId, "N/A", repoFullName, branch);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid signature"));
            }

            int appPort = project.getAppPort() != null ? project.getAppPort() : 3000;
            JenkinsBuildTriggerResult trigger;
            try {
                trigger = jenkinsService.triggerBuild(
                        project.getRepoUrl(),
                        branch,
                        project.getAppName(),
                        appPort,
                        project.getUserId()
                );
            } catch (Exception ex) {
                log.error("Jenkins trigger failed for projectId={} repo={} branch={}", project.getId(), repoFullName, branch, ex);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                        "error", "Jenkins trigger failed",
                        "projectId", project.getId()
                ));
            }

            project.setStatus("BUILDING");
            projectRepo.save(project);
            log.info("GitHub webhook accepted deliveryId={} projectId={} repo={} branch={} queueItemId={}",
                    deliveryId, project.getId(), repoFullName, branch, trigger.queueItemId());

            return ResponseEntity.accepted().body(Map.of(
                    "status", "accepted",
                    "projectId", project.getId(),
                    "queueUrl", trigger.queueUrl(),
                    "queueItemId", trigger.queueItemId()
            ));
        } catch (Exception e) {
            log.error("Failed to handle GitHub webhook", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "Webhook processing failed"));
        }
    }

    private String extractPayloadJson(String rawPayload, String contentType) {
        if (rawPayload == null) {
            return "";
        }

        String normalizedContentType = contentType == null ? "" : contentType.toLowerCase();
        boolean isFormEncoded = normalizedContentType.contains("application/x-www-form-urlencoded")
                || rawPayload.startsWith("payload=");

        if (!isFormEncoded) {
            return rawPayload;
        }

        String encodedPayload = rawPayload;
        int start = rawPayload.indexOf("payload=");
        if (start >= 0) {
            encodedPayload = rawPayload.substring(start + "payload=".length());
            int amp = encodedPayload.indexOf('&');
            if (amp >= 0) {
                encodedPayload = encodedPayload.substring(0, amp);
            }
        }
        return URLDecoder.decode(encodedPayload, StandardCharsets.UTF_8);
    }
}
