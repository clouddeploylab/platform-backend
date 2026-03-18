package com.ratana.monoloticappbackend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ProjectRequest(
        @NotBlank String repoUrl,
        @NotBlank String branch,
        @NotBlank
        @Pattern(regexp = "^[a-z0-9-]{3,40}$",
                 message = "appName must be 3-40 lowercase alphanumeric/hyphen chars")
        String appName,
        Integer appPort   // optional — defaults to framework convention
) {}
