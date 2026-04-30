package com.lazydevs.notification.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the DD-15 trust-boundary scrub: callers cannot set
 * {@code replayOf} via the REST send endpoints. The field is server-set
 * by the {@code POST /admin/dead-letter/{id}/replay} flow only; if a
 * client submits a value, it's logged at WARN and nulled before the
 * request reaches the service layer (the service would otherwise audit
 * a fake replay chain).
 */
class NotificationControllerReplayOfScrubbingTest {

    private NotificationService notificationService;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        NotificationController controller = new NotificationController(notificationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    void send_clearsClientSubmittedReplayOf() throws Exception {
        when(notificationService.send(any())).thenAnswer(this::okResponse);

        NotificationRequest body = NotificationRequest.builder()
                .notificationType("ORDER_CONFIRMATION")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .replayOf("attacker-spoofed-id")
                .build();

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).send(captor.capture());
        assertThat(captor.getValue().getReplayOf())
                .as("client-submitted replayOf must be scrubbed before reaching the service")
                .isNull();
    }

    @Test
    void send_blankReplayOf_isLeftAlone() throws Exception {
        // A blank string isn't a forge attempt; don't bother logging.
        // What matters is replayOf isn't carried forward as a real value.
        when(notificationService.send(any())).thenAnswer(this::okResponse);

        NotificationRequest body = NotificationRequest.builder()
                .notificationType("ORDER_CONFIRMATION")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .replayOf("   ")
                .build();

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).send(captor.capture());
        // Blank passes through as-is — it's not load-bearing for the
        // audit chain; only non-blank submissions are scrubbed.
        assertThat(captor.getValue().getReplayOf()).isEqualTo("   ");
    }

    private NotificationResponse okResponse(org.mockito.invocation.InvocationOnMock inv) {
        NotificationRequest req = inv.getArgument(0);
        return new NotificationResponse(
                "new-id", null, req.getTenantId(), req.getCallerId(),
                req.getChannel(), "smtp", NotificationStatus.SENT, "msg-1",
                null, null, Instant.now(), Instant.now(), Instant.now(), null);
    }
}
