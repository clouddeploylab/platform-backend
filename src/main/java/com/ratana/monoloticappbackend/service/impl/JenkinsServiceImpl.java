package com.ratana.monoloticappbackend.service.impl;

import com.ratana.monoloticappbackend.service.JenkinsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
public class JenkinsServiceImpl implements JenkinsService {

    @Value("${jenkins.url}")
    private String jenkinsUrl;

    @Value("${jenkins.token}")
    private String jenkinsToken;

    @Value("${jenkins.user}")
    private String jenkinsUser;

    private final RestTemplate rest = new RestTemplate();

    @Override
    public void triggerBuild(String repoUrl, String branch, String appName, int appPort) {
        String url = jenkinsUrl + "/job/deploy-pipeline/buildWithParameters" +
                "?REPO_URL=" + UriUtils.encode(repoUrl, StandardCharsets.UTF_8) +
                "&BRANCH=" + UriUtils.encode(branch, StandardCharsets.UTF_8) +
                "&APP_NAME=" + UriUtils.encode(appName, StandardCharsets.UTF_8) +
                "&APP_PORT=" + appPort;

        HttpHeaders headers = new HttpHeaders();
        String auth = Base64.getEncoder().encodeToString(
                (jenkinsUser + ":" + jenkinsToken).getBytes());
        headers.set("Authorization", "Basic " + auth);

        rest.postForEntity(url, new HttpEntity<>(headers), String.class);
    }
}

