package com.ratana.monoloticappbackend.controller;

import com.ratana.monoloticappbackend.service.JenkinsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/jenkins")
public class JenkinsController {

    private final JenkinsService jenkinsService;

    @GetMapping(value = "/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamBuildLogs(@RequestParam(required = false) String job, @RequestParam int build) {
        if (build <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "build must be greater than zero");
        }
        return jenkinsService.streamBuildLogs(job, build);
    }
}
