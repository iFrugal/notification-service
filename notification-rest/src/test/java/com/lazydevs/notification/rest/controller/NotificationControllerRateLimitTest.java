package com.lazydevs.notification.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.exception.RateLimitExceededException;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that {@link RateLimitExceededException} surfaces as
 * <strong>HTTP 429 Too Many Requests</strong> with the
 * {@code Retry-After} header set, per DD-12.
 *
 * <p>Standalone {@link MockMvcBuilders#standaloneSetup} (same approach as
 * {@code NotificationControllerIdempotencyTest}) so we don't pull Spring
 * Boot 4's split test-autoconfigure into this module's classpath.
 */
class NotificationControllerRateLimitTest {

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
    void rateLimitExceeded_returns429WithRetryAfter() throws Exception {
        when(notificationService.send(any()))
                .thenThrow(new RateLimitExceededException("acme", "billing-svc", "email",
                        Duration.ofSeconds(3)));

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "acme")
                        .header("X-Service-Id", "billing-svc")
                        .content(objectMapper.writeValueAsString(simpleBody())))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(3));
    }

    @Test
    void subSecondRetry_roundsUpToOneSecond() throws Exception {
        // Retry-After is whole-seconds per RFC 7231 §7.1.3 — a 200ms
        // remaining-bucket-time still surfaces as 1.
        when(notificationService.send(any()))
                .thenThrow(new RateLimitExceededException("acme", "billing-svc", "email",
                        Duration.ofMillis(200)));

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "acme")
                        .content(objectMapper.writeValueAsString(simpleBody())))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"));
    }

    private static NotificationRequest simpleBody() {
        return NotificationRequest.builder()
                .notificationType("TEST")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .build();
    }
}
