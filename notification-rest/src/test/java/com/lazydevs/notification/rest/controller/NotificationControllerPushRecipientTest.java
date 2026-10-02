package com.lazydevs.notification.rest.controller;

import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.PushRecipient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A push request in the 1.1 shape and in the 1.2 shape ({@code fid},
 * {@code deviceTokens}) binds through the HTTP message converter the
 * controller actually uses.
 */
class NotificationControllerPushRecipientTest {

    private NotificationService notificationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        when(notificationService.send(any())).thenAnswer(inv -> NotificationResponse.sent(
                inv.getArgument(0), "fcm", "projects/p/messages/1", Instant.now(), Instant.now()));
        mockMvc = MockMvcBuilders.standaloneSetup(new NotificationController(notificationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void pushRecipient11Shape_binds() throws Exception {
        PushRecipient push = bind("""
                {"type":"PUSH","deviceToken":"token-1","title":"Hi","body":"There"}""");

        assertThat(push.deviceToken()).isEqualTo("token-1");
        assertThat(push.fid()).isNull();
        assertThat(push.deviceTokens()).isNull();
    }

    @Test
    void pushRecipient12Shape_binds() throws Exception {
        PushRecipient tokens = bind("""
                {"type":"PUSH","deviceTokens":["token-1","token-2"],"title":"Hi","body":"There"}""");
        PushRecipient fid = bind("""
                {"type":"PUSH","fid":"fid-0123456789abcdef","title":"Hi","body":"There"}""");

        assertThat(tokens.deviceTokens()).containsExactly("token-1", "token-2");
        assertThat(fid.fid()).isEqualTo("fid-0123456789abcdef");
    }

    private PushRecipient bind(String recipientJson) throws Exception {
        String body = """
                {"notificationType":"PROMO","channel":"PUSH","recipient":%s}""".formatted(recipientJson);
        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "acme")
                        .content(body))
                .andExpect(status().is2xxSuccessful());
        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService, atLeastOnce()).send(captor.capture());
        return (PushRecipient) captor.getValue().getRecipient();
    }
}
