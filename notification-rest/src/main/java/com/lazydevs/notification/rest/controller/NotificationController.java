package com.lazydevs.notification.rest.controller;

import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Notifications",
        description = "Send single, batch, and async notifications. "
                + "Subject to idempotency (DD-10), rate limiting (DD-12), "
                + "and retry + DLQ (DD-13) when those features are enabled.")
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
    @Operation(
            summary = "Send a single notification",
            description = "Synchronously dispatches the request to the resolved "
                    + "provider. When idempotency is configured and the request "
                    + "carries an `idempotencyKey`, replays of completed sends are "
                    + "served from the cache (with `X-Idempotent-Replay: true`). "
                    + "Concurrent duplicates return `409`. Rate-limit denials "
                    + "return `429` with `Retry-After`.",
            responses = {
                    @ApiResponse(responseCode = "200",
                            description = "Send completed (or cache replay).",
                            headers = @Header(name = IDEMPOTENT_REPLAY_HEADER,
                                    description = "Set to `true` for cache replays only.",
                                    schema = @Schema(type = "string"))),
                    @ApiResponse(responseCode = "409",
                            description = "Idempotency conflict — concurrent in-flight send.",
                            content = @Content(schema = @Schema(example =
                                    "{\"notificationId\":\"...\",\"status\":\"IN_PROGRESS\"}"))),
                    @ApiResponse(responseCode = "429",
                            description = "Rate limit exceeded; check Retry-After header.",
                            headers = @Header(name = "Retry-After",
                                    description = "Whole seconds to wait before retrying.",
                                    schema = @Schema(type = "integer"))),
                    @ApiResponse(responseCode = "403",
                            description = "Caller-id rejected by strict caller registry (DD-11)."),
            })
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
    @Operation(
            summary = "Send a batch of notifications synchronously",
            description = "Each entry is processed serially. The response array "
                    + "preserves request ordering — failures don't short-circuit "
                    + "the rest of the batch.")
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
    @Operation(
            summary = "Send a notification asynchronously",
            description = "Returns `202 Accepted` immediately; the actual "
                    + "dispatch (including any retries) happens on a "
                    + "virtual-thread executor. Final outcome is reflected "
                    + "in the audit record under the same `requestId`.",
            responses = @ApiResponse(responseCode = "202",
                    description = "Request accepted; processing in background."))
    public ResponseEntity<NotificationResponse> sendAsync(@Valid @RequestBody NotificationRequest request) {
        log.debug("Received async notification request: channel={}, type={}",
                request.getChannel(), request.getNotificationType());

        // Trigger async send
        notificationService.sendAsync(request);

        // Return accepted response
        return ResponseEntity.accepted().body(NotificationResponse.accepted(request));
    }
}
