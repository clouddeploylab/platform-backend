package com.ratana.monoloticappbackend.dto;

import jakarta.validation.constraints.NotBlank;

public record WebhookCreateRequest(
        @NotBlank String name,
        Boolean autoDeployEnabled,
        Boolean createOnProvider
) {
}
