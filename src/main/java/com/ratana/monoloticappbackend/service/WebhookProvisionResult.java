package com.ratana.monoloticappbackend.service;

public record WebhookProvisionResult(
        String webhookId,
        boolean autoCreated
) {
}
