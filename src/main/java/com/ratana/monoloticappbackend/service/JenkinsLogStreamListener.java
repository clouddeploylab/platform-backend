package com.ratana.monoloticappbackend.service;

public interface JenkinsLogStreamListener {
    void onOpen(String jobName, int buildNumber) throws Exception;

    void onLog(String chunk, long nextOffset, int buildNumber) throws Exception;

    default void onQueued(int queueItemId, String message) throws Exception {
        // Optional callback for queue progress.
    }

    void onHeartbeat(String timestamp) throws Exception;

    void onDone(int buildNumber) throws Exception;

    void onError(String message, String detail) throws Exception;
}
