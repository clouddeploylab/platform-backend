package com.ratana.monoloticappbackend.controller;


import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects")
public class ProjectController {

    @PostMapping("/api/projects")
    public ResponseEntity<?> createProject(@RequestBody ProjectRequest req,
                                           HttpServletRequest httpReq) {
        String userId = extractUserFromRequest(httpReq);
        String appName = req.getAppName();

        // 1. Write GitOps manifests (service + ingress; Jenkins writes deployment)
        gitOpsService.createManifests(appName);

        // 2. Create ArgoCD Application (idempotent — skip if already exists)
        argoCdService.createApplicationIfAbsent(appName);

        // 3. Trigger Jenkins CI
        jenkinsService.triggerBuild(req.getRepoUrl(), req.getBranch(), appName);

        // 4. Persist project record
        Project project = new Project();
        project.setUserId(userId);
        project.setAppName(appName);
        project.setRepoUrl(req.getRepoUrl());
        project.setStatus("BUILDING");
        project.setUrl("https://" + appName + ".yourplatform.com");
        projectRepo.save(project);

        return ResponseEntity.ok(Map.of(
                "appName", appName,
                "url", project.getUrl(),
                "status", "BUILDING"
        ));
    }

}
