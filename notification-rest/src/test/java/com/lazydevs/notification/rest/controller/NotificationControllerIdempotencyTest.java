package com.lazydevs.notification.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.exception.IdempotencyInProgressException;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the controller / advice surface returns the DD-10 spec
 * 409 body shape — {@code {"notificationId":"...","status":"IN_PROGRESS"}}
 * — when the service throws {@link IdempotencyInProgressException}.
 *
 * <p>Uses a standalone {@link MockMvcBuilders#standaloneSetup} rather than
 * {@code @WebMvcTest} to avoid pulling Spring Boot 4's split test-autoconfigure
 * into this module's test classpath. Standalone setup is sufficient here
 * because we're testing the controller-advice contract, not auto-configuration.
 */
class NotificationControllerIdempotencyTest {

    private NotificationService notificationService;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        NotificationController controller = new NotificationController(notificationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
    }

    @Test
    void duplicateInFlight_returns409WithSpecBody() throws Exception {
        when(notificationService.send(any()))
                .thenThrow(new IdempotencyInProgressException("orig-req-12345"));

        NotificationRequest body = NotificationRequest.builder()
                .notificationType("ORDER_CONFIRMATION")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .idempotencyKey("idem-clash")
                .build();

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "acme")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.notificationId").value("orig-req-12345"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void replayedResponse_setsIdempotentReplayHeader_butDoesNotLeakFlagIntoBody() throws Exception {
        // Service returns a replay-stamped NotificationResponse — what
        // DefaultNotificationService actually does on a cached idempotency hit.
        NotificationResponse cached = new NotificationResponse(
                "orig-req-99", "corr-99", "acme", Channel.EMAIL,
                "smtp", NotificationStatus.SENT, "msg-99",
                null, null,
                Instant.parse("2026-04-27T10:00:00Z"),
                Instant.parse("2026-04-27T10:00:01Z"),
                Instant.parse("2026-04-27T10:00:01Z"),
                null);
        when(notificationService.send(any()))
                .thenReturn(NotificationResponse.replayedFrom(cached));

        NotificationRequest body = NotificationRequest.builder()
                .notificationType("ORDER_CONFIRMATION")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .idempotencyKey("idem-replay")
                .build();

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "acme")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                // Header SHOULD be set on replays.
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                // Body should carry the original send's data — same requestId,
                // same providerMessageId, original timestamps.
                .andExpect(jsonPath("$.requestId").value("orig-req-99"))
                .andExpect(jsonPath("$.providerMessageId").value("msg-99"))
                // Body must NOT include the idempotentReplay flag — the
                // contract is "header only" per DD-10.
                .andExpect(jsonPath("$.idempotentReplay").doesNotExist());
    }

    @Test
    void freshResponse_doesNotSetIdempotentReplayHeader() throws Exception {
        // No replay flag = fresh response = no header.
        NotificationResponse fresh = new NotificationResponse(
                "fresh-req", "corr-fresh", "acme", Channel.EMAIL,
                "smtp", NotificationStatus.SENT, "msg-fresh",
                null, null,
                Instant.now(), Instant.now(), Instant.now(),
                null);
        when(notificationService.send(any())).thenReturn(fresh);

        NotificationRequest body = NotificationRequest.builder()
                .notificationType("ORDER_CONFIRMATION")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .idempotencyKey("idem-fresh")
                .build();

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "acme")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Idempotent-Replay"));
    }
}
