package com.ratana.monoloticappbackend.service.impl;

import com.ratana.monoloticappbackend.service.GitOpsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;
import java.util.Map;

/**
 * Writes service.yaml + ingress.yaml to the GitOps repository via the GitHub
 * Contents API (no local git required on the backend).
 * Jenkins will write deployment.yaml during the build stage.
 */
@Slf4j
@Service
public class GitOpsServiceImpl implements GitOpsService {

    @Value("${gitops.repo-url}")
    private String gitopsRepoUrl;   // e.g. https://github.com/org/platform-gitops.git

    @Value("${gitops.token}")
    private String gitopsToken;     // GitHub PAT with repo write access

    @Value("${gitops.branch}")
    private String gitopsBranch;

    @Value("${platform.domain}")
    private String platformDomain;

    private final WebClient ghClient = WebClient.builder()
            .baseUrl("https://api.github.com")
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build();

    @Override
    public void createManifests(String appName, String repoUrl, int appPort) {
        String[] parts = gitopsRepoUrl
                .replace("https://github.com/", "")
                .replace(".git", "")
                .split("/");
        String owner = parts[0];
        String repo  = parts[1];

        String serviceYaml = """
                apiVersion: v1
                kind: Service
                metadata:
                  name: %s
                  namespace: apps
                spec:
                  selector:
                    app: %s
                  ports:
                    - name: http
                      port: 80
                      targetPort: %d
                  type: ClusterIP
                """.formatted(appName, appName, appPort);

        String ingressYaml = """
                apiVersion: networking.k8s.io/v1
                kind: Ingress
                metadata:
                  name: %s
                  namespace: apps
                  annotations:
                    nginx.ingress.kubernetes.io/rewrite-target: /
                    cert-manager.io/cluster-issuer: letsencrypt-prod
                spec:
                  ingressClassName: nginx
                  tls:
                    - hosts:
                        - %s.%s
                      secretName: %s-tls
                  rules:
                    - host: %s.%s
                      http:
                        paths:
                          - path: /
                            pathType: Prefix
                            backend:
                              service:
                                name: %s
                                port:
                                  number: 80
                """.formatted(appName, appName, platformDomain, appName,
                              appName, platformDomain, appName);

        upsertFile(owner, repo, "apps/" + appName + "/service.yaml",
                   serviceYaml, "ci: init service manifest for " + appName);
        upsertFile(owner, repo, "apps/" + appName + "/ingress.yaml",
                   ingressYaml, "ci: init ingress manifest for " + appName);

        log.info("GitOps manifests created for app '{}'", appName);
    }

    private void upsertFile(String owner, String repo, String path,
                             String content, String message) {
        String encoded = Base64.getEncoder().encodeToString(content.getBytes());
        String uri = "/repos/{owner}/{repo}/contents/{path}";

        // Check if file exists to get its SHA (required for update)
        String sha = null;
        try {
            Map<?, ?> existing = ghClient.get()
                    .uri(uri, owner, repo, path)
                    .header("Authorization", "Bearer " + gitopsToken)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (existing != null) sha = (String) existing.get("sha");
        } catch (Exception ignored) {}

        Map<String, Object> body = sha != null
                ? Map.of("message", message, "content", encoded,
                         "branch", gitopsBranch, "sha", sha)
                : Map.of("message", message, "content", encoded,
                         "branch", gitopsBranch);

        ghClient.put()
                .uri(uri, owner, repo, path)
                .header("Authorization", "Bearer " + gitopsToken)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }
}
