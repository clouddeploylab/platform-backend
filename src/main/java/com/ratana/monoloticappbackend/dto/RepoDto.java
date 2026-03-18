package com.ratana.monoloticappbackend.dto;

public record RepoDto(
        String fullName,
        String cloneUrl,
        String defaultBranch,
        boolean isPrivate,
        String description
) {}
