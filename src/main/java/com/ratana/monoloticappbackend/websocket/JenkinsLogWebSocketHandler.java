package com.ratana.monoloticappbackend.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratana.monoloticappbackend.service.JenkinsLogStreamListener;
import com.ratana.monoloticappbackend.service.JenkinsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class JenkinsLogWebSocketHandler extends TextWebSocketHandler {

    private final JenkinsService jenkinsService;
    private final ObjectMapper objectMapper;
    private final Map<String, AtomicBoolean> liveStreams = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, String> params = parseQueryParams(session.getUri());
        String jobName = params.getOrDefault("job", "");
        String buildRaw = params.get("build");
        String queueItemRaw = params.get("queueItem");

        Integer buildNumber = parsePositiveInteger(buildRaw);
        Integer queueItemId = parsePositiveInteger(queueItemRaw);

        if (buildNumber == null && queueItemId == null) {
            send(session, "error", Map.of(
                    "message", "Invalid stream request",
                    "detail", "Provide a positive 'build' or 'queueItem' query parameter"
            ));
            closeSession(session, CloseStatus.BAD_DATA);
            return;
        }

        AtomicBoolean streamOpen = new AtomicBoolean(true);
        liveStreams.put(session.getId(), streamOpen);

        JenkinsLogStreamListener listener = new JenkinsLogStreamListener() {
            @Override
            public void onOpen(String resolvedJobName, int resolvedBuildNumber) throws Exception {
                send(session, "open", Map.of(
                        "job", resolvedJobName,
                        "build", resolvedBuildNumber
                ));
            }

            @Override
            public void onLog(String chunk, long nextOffset, int resolvedBuildNumber) throws Exception {
                send(session, "log", Map.of(
                        "chunk", chunk,
                        "offset", nextOffset,
                        "build", resolvedBuildNumber
                ));
            }

            @Override
            public void onQueued(int resolvedQueueItemId, String message) throws Exception {
                send(session, "queued", Map.of(
                        "queueItemId", resolvedQueueItemId,
                        "message", message
                ));
            }

            @Override
            public void onHeartbeat(String timestamp) throws Exception {
                send(session, "heartbeat", Map.of("timestamp", timestamp));
            }

            @Override
            public void onDone(int resolvedBuildNumber) throws Exception {
                send(session, "done", Map.of(
                        "message", "Log stream completed",
                        "build", resolvedBuildNumber
                ));
                closeSession(session, CloseStatus.NORMAL);
            }

            @Override
            public void onError(String message, String detail) throws Exception {
                send(session, "error", Map.of(
                        "message", message,
                        "detail", detail
                ));
                closeSession(session, CloseStatus.SERVER_ERROR);
            }
        };

        if (buildNumber != null) {
            jenkinsService.streamBuildLogsAsync(jobName, buildNumber, streamOpen, listener);
        } else {
            jenkinsService.streamBuildLogsFromQueueAsync(jobName, queueItemId, streamOpen, listener);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AtomicBoolean streamOpen = liveStreams.remove(session.getId());
        if (streamOpen != null) {
            streamOpen.set(false);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("WebSocket transport error for session {}: {}", session.getId(), exception.getMessage());
        closeSession(session, CloseStatus.SERVER_ERROR);
    }

    private void send(WebSocketSession session, String type, Map<String, Object> payload) throws Exception {
        if (!session.isOpen()) {
            return;
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.putAll(payload);

        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
            }
        }
    }

    private void closeSession(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        AtomicBoolean streamOpen = liveStreams.get(session.getId());
        if (streamOpen != null) {
            streamOpen.set(false);
        }
        if (session.isOpen()) {
            session.close(closeStatus);
        }
    }

    private Map<String, String> parseQueryParams(URI uri) {
        if (uri == null) {
            return Map.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        UriComponentsBuilder.fromUri(uri)
                .build()
                .getQueryParams()
                .forEach((key, value) -> params.put(key, value.isEmpty() ? "" : value.get(0)));
        return params;
    }

    private Integer parsePositiveInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
