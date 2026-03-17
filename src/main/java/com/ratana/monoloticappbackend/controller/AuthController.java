package com.ratana.monoloticappbackend.controller;

import com.ratana.monoloticappbackend.model.GitHubToken;
import com.ratana.monoloticappbackend.repository.GitHubTokenRepository;
import com.ratana.monoloticappbackend.service.TokenEncryptionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    TokenEncryptionService encryptionService;
    @Autowired
    GitHubTokenRepository tokenRepo;

    @PostMapping("/github")
    public ResponseEntity<?> storeToken(@RequestBody TokenRequest req,
                                        HttpServletRequest httpReq) {
        // Extract user ID from your JWT/session validation (see security config)
        String userId = extractUserFromRequest(httpReq);

        try {
            String encrypted = encryptionService.encrypt(req.getToken());
            GitHubToken token = tokenRepo.findByUserId(userId)
                    .orElse(new GitHubToken());
            token.setUserId(userId);
            token.setEncryptedToken(encrypted);
            token.setUpdatedAt(Instant.now());
            if (token.getCreatedAt() == null) token.setCreatedAt(Instant.now());
            tokenRepo.save(token);
            return ResponseEntity.ok(Map.of("status", "stored"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to store token"));
        }
    }
}