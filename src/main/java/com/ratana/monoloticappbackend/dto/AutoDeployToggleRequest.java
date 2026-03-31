package com.ratana.monoloticappbackend.dto;

import jakarta.validation.constraints.NotNull;

public record AutoDeployToggleRequest(
        @NotNull Boolean enabled
) {
}
