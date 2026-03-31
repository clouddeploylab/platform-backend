package com.ratana.monoloticappbackend.service.impl;

import com.ratana.monoloticappbackend.model.GitHubToken;
import com.ratana.monoloticappbackend.repository.GitHubTokenRepository;
import com.ratana.monoloticappbackend.service.TokenEncryptionService;
import com.ratana.monoloticappbackend.service.WebhookProvisionResult;
import com.ratana.monoloticappbackend.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookServiceImpl implements WebhookService {
    @Value("${platform.webhook.public-base-url:http://localhost:8080}")
    private String publicBaseUrl;

    private final GitHubTokenRepository tokenRepo;
    private final TokenEncryptionService tokenEncryptionService;

    private final WebClient githubClient = WebClient.builder()
            .baseUrl("https://api.github.com")
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build();

    @Override
    public String generateWebhookSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String encryptSecret(String plainSecret) {
        try {
            return tokenEncryptionService.encrypt(plainSecret);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt webhook secret", e);
        }
    }

    @Override
    public String decryptSecret(String encryptedSecret) {
        try {
            return tokenEncryptionService.decrypt(encryptedSecret);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt webhook secret", e);
        }
    }

    @Override
    public String githubWebhookUrl() {
        return publicBaseUrl.replaceAll("/+$", "") + "/api/v1/webhooks/github";
    }

    @Override
    public boolean verifyGithubSignature(String secret, String rawPayload, String signatureHeader) {
        if (secret == null || secret.isBlank()) {
            return false;
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }

        String expected = "sha256=" + hmacSha256(secret, rawPayload);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8)
        );
    }

    @Override
    public WebhookProvisionResult ensureGitHubPushWebhook(String userId, String repoFullName, String webhookSecret) {
        if (repoFullName == null || repoFullName.isBlank()) {
            throw new IllegalArgumentException("repoFullName is required");
        }

        String token = resolveGithubToken(userId);
        String webhookUrl = githubWebhookUrl();

        List<Map<String, Object>> existingHooks = githubClient.get()
                .uri("/repos/{repo}/hooks", repoFullName)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block();

        if (existingHooks != null) {
            for (Map<String, Object> hook : existingHooks) {
                Object configObj = hook.get("config");
                if (!(configObj instanceof Map<?, ?> configMap)) {
                    continue;
                }
                Object urlObj = configMap.get("url");
                if (urlObj instanceof String existingUrl && webhookUrl.equalsIgnoreCase(existingUrl)) {
                    String hookId = extractHookId(hook);
                    if (hookId != null) {
                        updateGitHubWebhookSecret(userId, repoFullName, hookId, webhookSecret);
                    }
                    return new WebhookProvisionResult(hookId, false);
                }
            }
        }

        Map<String, Object> payload = Map.of(
                "name", "web",
                "active", true,
                "events", List.of("push"),
                "config", Map.of(
                        "url", webhookUrl,
                        "content_type", "json",
                        "secret", webhookSecret,
                        "insecure_ssl", "0"
                )
        );

        Map<String, Object> createdHook = githubClient.post()
                .uri("/repos/{repo}/hooks", repoFullName)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();

        return new WebhookProvisionResult(extractHookId(createdHook), true);
    }

    @Override
    public boolean updateGitHubWebhookSecret(String userId, String repoFullName, String webhookId, String webhookSecret) {
        if (webhookId == null || webhookId.isBlank()) {
            return false;
        }
        String token = resolveGithubToken(userId);

        Map<String, Object> payload = Map.of(
                "active", true,
                "events", List.of("push"),
                "config", Map.of(
                        "url", githubWebhookUrl(),
                        "content_type", "json",
                        "secret", webhookSecret,
                        "insecure_ssl", "0"
                )
        );

        try {
            githubClient.patch()
                    .uri("/repos/{repo}/hooks/{hookId}", repoFullName, webhookId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            return true;
        } catch (WebClientResponseException.NotFound notFound) {
            return false;
        }
    }

    @Override
    public boolean deleteGitHubWebhook(String userId, String repoFullName, String webhookId) {
        if (webhookId == null || webhookId.isBlank()) {
            return false;
        }

        String token = resolveGithubToken(userId);
        try {
            githubClient.delete()
                    .uri("/repos/{repo}/hooks/{hookId}", repoFullName, webhookId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            return true;
        } catch (WebClientResponseException.NotFound notFound) {
            return true;
        }
    }

    @Override
    public String resolveGithubToken(String userId) {
        GitHubToken tokenEntity = tokenRepo.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("No GitHub token found for user: " + userId));

        try {
            return tokenEncryptionService.decrypt(tokenEntity.getEncryptedToken());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt GitHub token for user: " + userId, e);
        }
    }

    @Override
    public String hmacSha256(String secret, String payload) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            hmac.init(keySpec);
            byte[] digest = hmac.doFinal(payload.getBytes(StandardCharsets.UTF_8));

            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format(Locale.ROOT, "%02x", value));
            }
            return builder.toString();
        } catch (Exception e) {
            log.error("Failed to compute HMAC-SHA256", e);
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }

    private String extractHookId(Map<String, Object> hook) {
        if (hook == null) {
            return null;
        }
        Object idObj = hook.get("id");
        if (idObj == null) {
            return null;
        }
        return String.valueOf(idObj);
    }
}
