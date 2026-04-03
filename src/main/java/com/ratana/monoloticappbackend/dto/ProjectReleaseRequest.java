package com.ratana.monoloticappbackend.dto;

public record ProjectReleaseRequest(
        Integer buildNumber,
        String framework,
        String statusMessage
) {
}
