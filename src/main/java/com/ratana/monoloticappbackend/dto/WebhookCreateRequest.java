package com.ratana.monoloticappbackend.dto;

import jakarta.validation.constraints.NotBlank;

public record WebhookCreateRequest(
        @NotBlank String name,
        String branch,
        Boolean autoDeployEnabled,
        Boolean createOnProvider
) {
}
