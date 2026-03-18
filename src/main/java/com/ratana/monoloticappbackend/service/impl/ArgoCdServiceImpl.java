package com.ratana.monoloticappbackend.service.impl;

import com.ratana.monoloticappbackend.service.ArgoCdService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Slf4j
@Service
public class ArgoCdServiceImpl implements ArgoCdService {

    @Value("${argocd.url}")
    private String argocdUrl;

    @Value("${argocd.token}")
    private String argocdToken;

    @Value("${argocd.namespace}")
    private String argocdNamespace;

    @Value("${gitops.repo-url}")
    private String gitopsRepoUrl;

    @Value("${gitops.branch}")
    private String gitopsBranch;

    @Value("${platform.domain}")
    private String platformDomain;

    @Override
    public void createApplicationIfAbsent(String appName) {
        WebClient client = WebClient.builder()
                .baseUrl(argocdUrl)
                .defaultHeader("Authorization", "Bearer " + argocdToken)
                .defaultHeader("Content-Type", "application/json")
                .build();

        // Check if app already exists
        boolean exists = Boolean.TRUE.equals(
                client.get()
                        .uri("/api/v1/applications/{name}", appName)
                        .retrieve()
                        .onStatus(HttpStatusCode::is4xxClientError, r -> reactor.core.publisher.Mono.empty())
                        .bodyToMono(Map.class)
                        .map(body -> body != null && body.containsKey("metadata"))
                        .onErrorReturn(false)
                        .block()
        );

        if (exists) {
            log.info("ArgoCD application '{}' already exists — skipping creation.", appName);
            return;
        }

        Map<String, Object> appSpec = Map.of(
                "metadata", Map.of(
                        "name", appName,
                        "namespace", argocdNamespace
                ),
                "spec", Map.of(
                        "project", "default",
                        "source", Map.of(
                                "repoURL", gitopsRepoUrl,
                                "targetRevision", gitopsBranch,
                                "path", "apps/" + appName
                        ),
                        "destination", Map.of(
                                "server", "https://kubernetes.default.svc",
                                "namespace", "apps"
                        ),
                        "syncPolicy", Map.of(
                                "automated", Map.of(
                                        "prune", true,
                                        "selfHeal", true
                                ),
                                "syncOptions", java.util.List.of("CreateNamespace=true")
                        )
                )
        );

        client.post()
                .uri("/api/v1/applications")
                .bodyValue(appSpec)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        log.info("ArgoCD application '{}' created successfully.", appName);
    }
}
