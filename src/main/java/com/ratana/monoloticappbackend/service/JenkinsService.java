package com.ratana.monoloticappbackend.service;

public interface JenkinsService {
    void triggerBuild(String repoUrl, String branch, String appName, int appPort, String userId);
}
