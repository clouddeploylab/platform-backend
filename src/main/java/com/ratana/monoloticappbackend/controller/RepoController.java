package com.ratana.monoloticappbackend.controller;

import com.ratana.monoloticappbackend.dto.RepoDto;
import com.ratana.monoloticappbackend.service.GitHubService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/repos")
@RequiredArgsConstructor
public class RepoController {

    private final GitHubService gitHubService;

    @GetMapping
    public ResponseEntity<?> getRepos(Authentication auth) {
        String userId = extractUserFromRequest(auth);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        try {
            List<RepoDto> repos = gitHubService.getReposForUser(userId);
            return ResponseEntity.ok(repos);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private String extractUserFromRequest(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getSubject();
        }
        return auth != null ? auth.getName() : null;
    }
}
