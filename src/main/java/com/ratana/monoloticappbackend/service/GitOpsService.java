package com.ratana.monoloticappbackend.service;

public interface GitOpsService {
    /**
     * Creates the service.yaml and ingress.yaml in the GitOps repo
     * under /apps/<appName>/ via the GitHub API.
     * Jenkins handles deployment.yaml during the CI build.
     */
    void createManifests(String appName, String repoUrl, int appPort);
}
