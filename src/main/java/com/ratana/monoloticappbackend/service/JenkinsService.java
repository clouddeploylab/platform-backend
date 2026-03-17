package com.ratana.monoloticappbackend.service;

import org.springframework.stereotype.Service;

@Service
public interface JenkinsService {
    void triggerBuild(String repoUrl, String branch, String appName);
}
