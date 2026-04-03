package com.ratana.monoloticappbackend.dto;

import jakarta.validation.constraints.NotBlank;

public record ProjectRollbackRequest(
        @NotBlank String releaseId
) {
}
