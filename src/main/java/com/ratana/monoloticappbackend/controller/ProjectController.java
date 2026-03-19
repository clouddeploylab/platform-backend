package com.ratana.monoloticappbackend.controller;

import com.ratana.monoloticappbackend.dto.ProjectRequest;
import com.ratana.monoloticappbackend.model.Project;
import com.ratana.monoloticappbackend.repository.ProjectRepository;
import com.ratana.monoloticappbackend.service.JenkinsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final JenkinsService jenkinsService;
    private final ProjectRepository projectRepo;

    @Value("${platform.domain}")
    private String platformDomain;

    @PostMapping
    public ResponseEntity<?> createProject(@Valid @RequestBody ProjectRequest req, Authentication auth) {
        String userId = extractUserFromRequest(auth);
        String appName = req.appName();

        if (projectRepo.existsByAppName(appName)) {
            return ResponseEntity.badRequest().body(Map.of("error", "App name already in use"));
        }

        int port = req.appPort() != null ? req.appPort() : 3000;

        try {
            // 1. Trigger Jenkins CI build (this job writes deployment/service/ingress to GitOps)
            jenkinsService.triggerBuild(req.repoUrl(), req.branch(), appName, port, userId);

            // 2. Persist project record
            Project project = new Project();
            project.setUserId(userId);
            project.setAppName(appName);
            project.setRepoUrl(req.repoUrl());
            project.setBranch(req.branch());
            project.setAppPort(port);
            project.setStatus("BUILDING");
            project.setUrl("https://" + appName + "." + platformDomain);
            projectRepo.save(project);

            log.info("Project {} created and deployment pipeline triggered", appName);

            return ResponseEntity.ok(project);
        } catch (Exception e) {
            log.error("Failed to create project deployment flow for app: " + appName, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Deployment failed to start"));
        }
    }

    @GetMapping
    public ResponseEntity<?> listProjects(Authentication auth) {
        String userId = extractUserFromRequest(auth);
        return ResponseEntity.ok(projectRepo.findByUserId(userId));
    }

    private String extractUserFromRequest(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getSubject();
        }
        return auth != null ? auth.getName() : "anonymous";
    }
}
