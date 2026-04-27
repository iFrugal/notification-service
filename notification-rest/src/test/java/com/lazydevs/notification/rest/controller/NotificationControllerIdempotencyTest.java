package com.lazydevs.notification.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.exception.IdempotencyInProgressException;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
}
