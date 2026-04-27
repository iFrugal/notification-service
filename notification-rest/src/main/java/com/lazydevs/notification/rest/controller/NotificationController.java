package com.lazydevs.notification.rest.controller;

import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for sending notifications.
 */
@Slf4j
@RestController
@RequestMapping("${notification.rest.base-path:/api/v1}/notifications")
@ConditionalOnProperty(prefix = "notification.rest", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * HTTP response header set to {@code "true"} when the response was
     * served from the idempotency cache rather than from a fresh provider
     * call. Lets callers with side-effect-on-success flows distinguish
     * "I just caused a send" from "I'm seeing the result of a past send"
     * without parsing timestamps. See DD-10 §REST-API-behaviour.
     */
    static final String IDEMPOTENT_REPLAY_HEADER = "X-Idempotent-Replay";

    /**
     * Send a single notification.
     */
    @PostMapping
    public ResponseEntity<NotificationResponse> send(@Valid @RequestBody NotificationRequest request) {
        log.debug("Received notification request: channel={}, type={}",
                request.getChannel(), request.getNotificationType());

        NotificationResponse response = notificationService.send(request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (Boolean.TRUE.equals(response.idempotentReplay())) {
            builder = builder.header(IDEMPOTENT_REPLAY_HEADER, "true");
        }
        return builder.body(response);
    }

    /**
     * Send multiple notifications in batch.
     */
    @PostMapping("/batch")
    public ResponseEntity<List<NotificationResponse>> sendBatch(
            @Valid @RequestBody List<NotificationRequest> requests) {
        log.debug("Received batch notification request: count={}", requests.size());

        List<NotificationResponse> responses = notificationService.sendBatch(requests);
        return ResponseEntity.ok(responses);
    }

    /**
     * Send a notification asynchronously.
     * Returns immediately with ACCEPTED status.
     */
    @PostMapping("/async")
    public ResponseEntity<NotificationResponse> sendAsync(@Valid @RequestBody NotificationRequest request) {
        log.debug("Received async notification request: channel={}, type={}",
                request.getChannel(), request.getNotificationType());

        // Trigger async send
        notificationService.sendAsync(request);

        // Return accepted response
        return ResponseEntity.accepted().body(NotificationResponse.accepted(request));
    }
}
