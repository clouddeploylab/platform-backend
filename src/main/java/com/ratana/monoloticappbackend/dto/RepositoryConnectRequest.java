package com.ratana.monoloticappbackend.dto;

import jakarta.validation.constraints.NotBlank;

public record RepositoryConnectRequest (
        @NotBlank
        String repoProvider,

        @NotBlank
        String repoUrl,

        @NotBlank
        String repoFullName,

        @NotBlank
        String branch,

        Boolean autoDeployEnabled
)
{}
