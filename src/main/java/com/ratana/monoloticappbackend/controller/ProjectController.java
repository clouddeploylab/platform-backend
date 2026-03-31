package com.ratana.monoloticappbackend.controller;

import com.ratana.monoloticappbackend.dto.AutoDeployToggleRequest;
import com.ratana.monoloticappbackend.dto.DeployProjectResponse;
import com.ratana.monoloticappbackend.dto.ProjectRequest;
import com.ratana.monoloticappbackend.dto.RepositoryConnectRequest;
import com.ratana.monoloticappbackend.dto.WebhookCreateRequest;
import com.ratana.monoloticappbackend.model.Project;
import com.ratana.monoloticappbackend.model.Workspace;
import com.ratana.monoloticappbackend.repository.ProjectRepository;
import com.ratana.monoloticappbackend.dto.JenkinsBuildTriggerResult;
import com.ratana.monoloticappbackend.service.JenkinsService;
import com.ratana.monoloticappbackend.service.WebhookProvisionResult;
import com.ratana.monoloticappbackend.service.WebhookService;
import com.ratana.monoloticappbackend.service.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final JenkinsService jenkinsService;
    private final ProjectRepository projectRepo;
    private final WebhookService webhookService;
    private final WorkspaceService workspaceService;

    @Value("${platform.domain}")
    private String platformDomain;

    private static final Pattern HTTPS_GITHUB = Pattern.compile("^https?://github\\.com/([^/]+)/([^/.]+)(?:\\.git)?/?$");
    private static final Pattern SSH_GITHUB = Pattern.compile("^git@github\\.com:([^/]+)/([^/.]+)(?:\\.git)?$");

    @PostMapping
    public ResponseEntity<?> createProject(@Valid @RequestBody ProjectRequest req, Authentication auth) {
        String userId = extractUserFromRequest(auth);
        String appName = req.appName();
        Workspace workspace = workspaceService.ensurePersonalWorkspace(userId, null);

        if (projectRepo.existsByWorkspaceIdAndAppName(workspace.getId(), appName)) {
            return ResponseEntity.badRequest().body(Map.of("error", "App name already in use"));
        }

        int port = req.appPort() != null ? req.appPort() : 3000;

        try {
            // 1. Trigger Jenkins CI build (this job writes deployment/service/ingress to GitOps)
            JenkinsBuildTriggerResult trigger = jenkinsService.triggerBuild(req.repoUrl(), req.branch(), appName, port, userId);

            // 2. Persist project record
            Project project = new Project();
            project.setUserId(userId);
            project.setWorkspaceId(workspace.getId());
            project.setAppName(appName);
            project.setRepoUrl(req.repoUrl());
            project.setBranch(req.branch());
            project.setRepoProvider("github");
            project.setRepoFullName(extractRepoFullName(req.repoUrl()).orElse(null));
            project.setAutoDeployEnabled(false);
            project.setAppPort(port);
            project.setStatus("BUILDING");
            project.setUrl("https://" + appName + "." + platformDomain);
            projectRepo.save(project);

            log.info("Project {} created and deployment pipeline triggered", appName);

            return ResponseEntity.ok(new DeployProjectResponse(
                    project,
                    trigger.jobName(),
                    trigger.queueUrl(),
                    trigger.queueItemId()
            ));
        } catch (Exception e) {
            log.error("Failed to create project deployment flow for app: " + appName, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Deployment failed to start"));
        }
    }

    @GetMapping
    public ResponseEntity<?> listProjects(Authentication auth) {
        String userId = extractUserFromRequest(auth);
        Workspace workspace = workspaceService.ensurePersonalWorkspace(userId, null);
        return ResponseEntity.ok(projectRepo.findByWorkspaceId(workspace.getId()));
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<?> getProject(@PathVariable String projectId, Authentication auth) {
        String userId = extractUserFromRequest(auth);
        Project project = projectRepo.findByUserIdAndId(userId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        return ResponseEntity.ok(project);
    }

    @PostMapping("/{projectId}/repository/connect")
    public ResponseEntity<?> connectRepository(
            @PathVariable String projectId,
            @Valid @RequestBody RepositoryConnectRequest req,
            Authentication auth
    ) {
        String userId = extractUserFromRequest(auth);
        Project project = projectRepo.findByUserIdAndId(userId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        String plainSecret = webhookService.generateWebhookSecret();
        String encryptedSecret = webhookService.encryptSecret(plainSecret);

        String provider = req.repoProvider().toLowerCase(Locale.ROOT);
        project.setRepoProvider(provider);
        project.setRepoUrl(req.repoUrl());
        project.setRepoFullName(req.repoFullName());
        project.setBranch(req.branch());
        project.setWebhookSecret(encryptedSecret);
        project.setWebhookName(project.getAppName() + "-webhook");
        project.setAutoDeployEnabled(req.autoDeployEnabled() == null || req.autoDeployEnabled());

        boolean webhookAutoCreated = false;
        if ("github".equals(provider) && Boolean.TRUE.equals(project.getAutoDeployEnabled())) {
            try {
                WebhookProvisionResult webhookResult = webhookService.ensureGitHubPushWebhook(userId, req.repoFullName(), plainSecret);
                webhookAutoCreated = webhookResult.autoCreated();
                project.setWebhookProviderId(webhookResult.webhookId());
            } catch (Exception e) {
                log.warn("Unable to auto-create GitHub webhook for project {}", project.getId(), e);
            }
        }
        projectRepo.save(project);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", project.getId());
        payload.put("repoProvider", project.getRepoProvider());
        payload.put("repoFullName", project.getRepoFullName());
        payload.put("branch", project.getBranch());
        payload.put("webhookName", project.getWebhookName());
        payload.put("webhookProviderId", project.getWebhookProviderId());
        payload.put("autoDeployEnabled", project.getAutoDeployEnabled());
        payload.put("webhookAutoCreated", webhookAutoCreated);

        Map<String, Object> webhook = new LinkedHashMap<>();
        webhook.put("url", webhookService.githubWebhookUrl());
        webhook.put("secret", plainSecret);
        webhook.put("events", "push");
        payload.put("webhook", webhook);

        return ResponseEntity.ok(payload);
    }

    @PatchMapping("/{projectId}/auto-deploy")
    public ResponseEntity<?> setAutoDeploy(
            @PathVariable String projectId,
            @Valid @RequestBody AutoDeployToggleRequest req,
            Authentication auth
    ) {
        String userId = extractUserFromRequest(auth);
        Project project = projectRepo.findByUserIdAndId(userId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        project.setAutoDeployEnabled(req.enabled());
        projectRepo.save(project);

        return ResponseEntity.ok(Map.of(
                "projectId", project.getId(),
                "autoDeployEnabled", project.getAutoDeployEnabled()
        ));
    }

    @GetMapping("/{projectId}/webhook")
    public ResponseEntity<?> getWebhook(
            @PathVariable String projectId,
            Authentication auth
    ) {
        String userId = extractUserFromRequest(auth);
        Project project = projectRepo.findByUserIdAndId(userId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        return ResponseEntity.ok(buildWebhookResponse(project, null, false));
    }

    @PostMapping("/{projectId}/webhook")
    public ResponseEntity<?> createWebhook(
            @PathVariable String projectId,
            @Valid @RequestBody WebhookCreateRequest req,
            Authentication auth
    ) {
        String userId = extractUserFromRequest(auth);
        Project project = projectRepo.findByUserIdAndId(userId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        if (!"github".equalsIgnoreCase(project.getRepoProvider())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only GitHub provider is supported right now"));
        }
        if (project.getRepoFullName() == null || project.getRepoFullName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Project repository is not connected"));
        }

        String plainSecret = webhookService.generateWebhookSecret();
        String encryptedSecret = webhookService.encryptSecret(plainSecret);
        boolean shouldAutoDeploy = req.autoDeployEnabled() == null || req.autoDeployEnabled();
        boolean createOnProvider = req.createOnProvider() == null || req.createOnProvider();
        boolean webhookAutoCreated = false;
        String configuredBranch = normalizeBranchValue(req.branch(), project.getBranch());

        if (configuredBranch.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Webhook branch is required"));
        }

        project.setWebhookName(req.name().trim());
        project.setBranch(configuredBranch);
        project.setWebhookSecret(encryptedSecret);
        project.setAutoDeployEnabled(shouldAutoDeploy);

        if (createOnProvider && shouldAutoDeploy) {
            try {
                WebhookProvisionResult result = webhookService.ensureGitHubPushWebhook(
                        userId,
                        project.getRepoFullName(),
                        plainSecret
                );
                project.setWebhookProviderId(result.webhookId());
                webhookAutoCreated = result.autoCreated();
            } catch (Exception e) {
                log.warn("Failed to provision remote webhook for project {}", project.getId(), e);
            }
        }

        projectRepo.save(project);
        return ResponseEntity.ok(buildWebhookResponse(project, plainSecret, webhookAutoCreated));
    }

    @PostMapping("/{projectId}/webhook/rotate")
    public ResponseEntity<?> rotateWebhookSecret(
            @PathVariable String projectId,
            Authentication auth
    ) {
        String userId = extractUserFromRequest(auth);
        Project project = projectRepo.findByUserIdAndId(userId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        if (!"github".equalsIgnoreCase(project.getRepoProvider())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only GitHub provider is supported right now"));
        }
        if (project.getRepoFullName() == null || project.getRepoFullName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Project repository is not connected"));
        }

        String plainSecret = webhookService.generateWebhookSecret();
        project.setWebhookSecret(webhookService.encryptSecret(plainSecret));

        boolean webhookAutoCreated = false;
        boolean syncedProvider = false;

        if (Boolean.TRUE.equals(project.getAutoDeployEnabled())) {
            String providerWebhookId = project.getWebhookProviderId();
            if (providerWebhookId != null && !providerWebhookId.isBlank()) {
                syncedProvider = webhookService.updateGitHubWebhookSecret(
                        userId,
                        project.getRepoFullName(),
                        providerWebhookId,
                        plainSecret
                );
            }

            if (!syncedProvider) {
                try {
                    WebhookProvisionResult result = webhookService.ensureGitHubPushWebhook(
                            userId,
                            project.getRepoFullName(),
                            plainSecret
                    );
                    project.setWebhookProviderId(result.webhookId());
                    webhookAutoCreated = result.autoCreated();
                    syncedProvider = true;
                } catch (Exception e) {
                    log.warn("Failed to sync rotated webhook secret to provider for project {}", project.getId(), e);
                }
            }
        }

        projectRepo.save(project);
        return ResponseEntity.ok(buildWebhookResponse(project, plainSecret, webhookAutoCreated, syncedProvider));
    }

    @DeleteMapping("/{projectId}/webhook")
    public ResponseEntity<?> deleteWebhook(
            @PathVariable String projectId,
            Authentication auth
    ) {
        String userId = extractUserFromRequest(auth);
        Project project = projectRepo.findByUserIdAndId(userId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        boolean providerDeleted = false;
        if ("github".equalsIgnoreCase(project.getRepoProvider())
                && project.getRepoFullName() != null
                && !project.getRepoFullName().isBlank()
                && project.getWebhookProviderId() != null
                && !project.getWebhookProviderId().isBlank()) {
            try {
                providerDeleted = webhookService.deleteGitHubWebhook(
                        userId,
                        project.getRepoFullName(),
                        project.getWebhookProviderId()
                );
            } catch (Exception e) {
                log.warn("Failed to delete remote webhook for project {}", project.getId(), e);
            }
        }

        project.setWebhookName(null);
        project.setWebhookSecret(null);
        project.setWebhookProviderId(null);
        project.setAutoDeployEnabled(false);
        projectRepo.save(project);

        return ResponseEntity.ok(Map.of(
                "status", "deleted",
                "projectId", project.getId(),
                "providerDeleted", providerDeleted
        ));
    }

    @PostMapping("/{projectId}/sync")
    public ResponseEntity<?> syncProject(@PathVariable String projectId, Authentication auth) {
        String userId = extractUserFromRequest(auth);
        Project project = projectRepo.findByUserIdAndId(userId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        int port = project.getAppPort() != null ? project.getAppPort() : 3000;

        try {
            JenkinsBuildTriggerResult trigger = jenkinsService.triggerBuild(
                    project.getRepoUrl(),
                    project.getBranch(),
                    project.getAppName(),
                    port,
                    project.getUserId()
            );
            project.setStatus("BUILDING");
            projectRepo.save(project);

            return ResponseEntity.accepted().body(Map.of(
                    "status", "accepted",
                    "jobName", trigger.jobName(),
                    "queueUrl", trigger.queueUrl(),
                    "queueItemId", trigger.queueItemId()
            ));
        } catch (Exception e) {
            log.error("Manual sync failed for project {}", project.getId(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "Failed to trigger Jenkins pipeline"));
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    private Map<String, Object> buildWebhookResponse(Project project, String plainSecret, boolean webhookAutoCreated) {
        return buildWebhookResponse(project, plainSecret, webhookAutoCreated, false);
    }

    private Map<String, Object> buildWebhookResponse(
            Project project,
            String plainSecret,
            boolean webhookAutoCreated,
            boolean syncedProvider
    ) {
        String secretForResponse = plainSecret;
        if (secretForResponse == null && project.getWebhookSecret() != null && !project.getWebhookSecret().isBlank()) {
            try {
                secretForResponse = webhookService.decryptSecret(project.getWebhookSecret());
            } catch (Exception ignored) {
                secretForResponse = null;
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", project.getId());
        payload.put("repoProvider", project.getRepoProvider());
        payload.put("repoFullName", project.getRepoFullName());
        payload.put("branch", project.getBranch());
        payload.put("name", project.getWebhookName());
        payload.put("autoDeployEnabled", project.getAutoDeployEnabled());
        payload.put("webhookConfigured", project.getWebhookSecret() != null && !project.getWebhookSecret().isBlank());
        payload.put("webhookProviderId", project.getWebhookProviderId());
        payload.put("webhookAutoCreated", webhookAutoCreated);
        payload.put("syncedProvider", syncedProvider);

        Map<String, Object> webhook = new LinkedHashMap<>();
        webhook.put("url", webhookService.githubWebhookUrl());
        webhook.put("events", "push");
        if (secretForResponse != null) {
            webhook.put("secret", secretForResponse);
        }
        payload.put("webhook", webhook);
        return payload;
    }

    private Optional<String> extractRepoFullName(String repoUrl) {
        Matcher httpsMatcher = HTTPS_GITHUB.matcher(repoUrl.trim());
        if (httpsMatcher.matches()) {
            return Optional.of(httpsMatcher.group(1) + "/" + httpsMatcher.group(2));
        }
        Matcher sshMatcher = SSH_GITHUB.matcher(repoUrl.trim());
        if (sshMatcher.matches()) {
            return Optional.of(sshMatcher.group(1) + "/" + sshMatcher.group(2));
        }
        return Optional.empty();
    }

    private String extractUserFromRequest(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getSubject();
        }
        return auth != null ? auth.getName() : "anonymous";
    }

    private String normalizeBranchValue(String candidate, String fallback) {
        String value = candidate;
        if (value == null || value.isBlank()) {
            value = fallback;
        }
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.startsWith("refs/heads/")) {
            normalized = normalized.substring("refs/heads/".length());
        }
        return normalized.trim();
    }
}
