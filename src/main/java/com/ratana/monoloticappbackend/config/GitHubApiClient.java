package com.ratana.monoloticappbackend.config;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

private final RestTemplate rest = new RestTemplate();

public List<RepoDto> fetchRepos(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + token);
    headers.set("Accept", "application/vnd.github+json");

    ResponseEntity<List<Map<String, Object>>> response = rest.exchange(
            "https://api.github.com/user/repos?per_page=100&sort=updated",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            new ParameterizedTypeReference<>() {}
    );

    return response.getBody().stream()
            .map(r -> new RepoDto(
                    (String) r.get("full_name"),
                    (String) r.get("clone_url"),
                    (String) r.get("default_branch"),
                    (Boolean) r.get("private")
            ))
            .collect(Collectors.toList());
}