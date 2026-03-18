package com.ratana.monoloticappbackend.controller;

import com.ratana.monoloticappbackend.model.GitHubToken;
import com.ratana.monoloticappbackend.repository.GitHubTokenRepository;
import com.ratana.monoloticappbackend.service.TokenEncryptionService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Duration BACKEND_TOKEN_TTL = Duration.ofHours(24);

    private final TokenEncryptionService encryptionService;
    private final GitHubTokenRepository tokenRepo;

    @Value("${spring.security.oauth2.resourceserver.jwt.secret}")
    private String jwtSecret;

    private final WebClient githubClient = WebClient.builder()
            .baseUrl("https://api.github.com")
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build();

    public record TokenRequest(String token) {}

    @PostMapping("/github")
    public ResponseEntity<?> storeToken(@RequestBody TokenRequest req) {
        if (req == null || req.token() == null || req.token().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Token is required"));
        }

        final String userId;
        try {
            userId = resolveGitHubUserId(req.token());
        } catch (Exception e) {
            log.error("Failed to verify GitHub access token", e);
            return ResponseEntity.status(502).body(Map.of("error", "Failed to verify token with GitHub"));
        }

        if (userId == null) {
            log.warn("GitHub token verification failed: token was rejected by GitHub API");
            return ResponseEntity.status(401).body(Map.of("error", "Invalid GitHub token"));
        }

        try {
            String encrypted = encryptionService.encrypt(req.token());
            GitHubToken tokenEntity = tokenRepo.findByUserId(userId).orElse(new GitHubToken());
            
            tokenEntity.setUserId(userId);
            tokenEntity.setEncryptedToken(encrypted);
            // IV is now stored inside the encrypted string format "iv:ciphertext" inside TokenEncryptionServiceImpl
            tokenEntity.setIv("embedded"); 
            
            tokenEntity.setUpdatedAt(Instant.now());
            if (tokenEntity.getCreatedAt() == null) {
                tokenEntity.setCreatedAt(Instant.now());
            }
            
            tokenRepo.save(tokenEntity);
            log.info("Successfully securely stored GitHub token for user {}", userId);

            String backendToken = createBackendToken(userId);
            return ResponseEntity.ok(Map.of(
                    "status", "Token stored securely",
                    "backendToken", backendToken
            ));
        } catch (Exception e) {
            log.error("Failed to encrypt/store token", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to store token"));
        }
    }

    private String resolveGitHubUserId(String githubToken) {
        try {
            Map<String, Object> user = githubClient.get()
                    .uri("/user")
                    .header("Authorization", "Bearer " + githubToken)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            if (user == null || user.get("id") == null) {
                return null;
            }

            return String.valueOf(user.get("id"));
        } catch (WebClientResponseException.Unauthorized ex) {
            return null;
        }
    }

    private String createBackendToken(String userId) {
        Instant now = Instant.now();
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));

        return Jwts.builder()
                .subject(userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(BACKEND_TOKEN_TTL)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
