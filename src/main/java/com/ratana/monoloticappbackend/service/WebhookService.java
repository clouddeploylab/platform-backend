package com.ratana.monoloticappbackend.service;

public interface WebhookService {
    String generateWebhookSecret();

    String encryptSecret(String plainSecret);

    String decryptSecret(String encryptedSecret);

    String githubWebhookUrl();

    boolean verifyGithubSignature(String secret, String rawPayload, String signatureHeader);

    WebhookProvisionResult ensureGitHubPushWebhook(String userId, String repoFullName, String webhookSecret);

    boolean updateGitHubWebhookSecret(String userId, String repoFullName, String webhookId, String webhookSecret);

    boolean deleteGitHubWebhook(String userId, String repoFullName, String webhookId);

    String resolveGithubToken(String userId);

    String hmacSha256(String secret, String payload);
}
