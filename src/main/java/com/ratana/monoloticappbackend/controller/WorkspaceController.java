package com.ratana.monoloticappbackend.controller;

import com.ratana.monoloticappbackend.model.Workspace;
import com.ratana.monoloticappbackend.repository.ProjectRepository;
import com.ratana.monoloticappbackend.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final ProjectRepository projectRepository;

    @GetMapping("/me")
    public ResponseEntity<?> getMyWorkspace(Authentication auth) {
        String userId = extractUserFromRequest(auth);
        Workspace workspace = workspaceService.ensurePersonalWorkspace(userId, null);

        long projectCount = projectRepository.countByWorkspaceId(workspace.getId());

        return ResponseEntity.ok(Map.of(
                "id", workspace.getId(),
                "name", workspace.getName(),
                "slug", workspace.getSlug(),
                "ownerUserId", workspace.getOwnerUserId(),
                "projectCount", projectCount,
                "createdAt", workspace.getCreatedAt()
        ));
    }

    private String extractUserFromRequest(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getSubject();
        }
        return auth != null ? auth.getName() : "anonymous";
    }
}
