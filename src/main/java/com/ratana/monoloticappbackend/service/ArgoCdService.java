package com.ratana.monoloticappbackend.service;

public interface ArgoCdService {
    /**
     * Creates an ArgoCD Application for the given appName pointing at the
     * GitOps repo. Idempotent — does nothing if the application already exists.
     */
    void createApplicationIfAbsent(String appName);
}
