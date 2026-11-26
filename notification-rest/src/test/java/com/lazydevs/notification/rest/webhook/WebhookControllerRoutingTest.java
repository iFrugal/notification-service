package com.lazydevs.notification.rest.webhook;

import com.lazydevs.notification.api.webhook.WebhookHandler;
import com.lazydevs.notification.api.webhook.WebhookRequest;
import com.lazydevs.notification.api.webhook.WebhookResponse;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.webhook.WebhookDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The generic {@code /{handlerId}/**} route: what a handler receives and how
 * its answer is written back.
 */
class WebhookControllerRoutingTest {

    private final AtomicReference<WebhookRequest> seen = new AtomicReference<>();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WebhookHandler echo = new WebhookHandler() {
            @Override
            public String id() {
                return "echo";
            }

            @Override
            public WebhookResponse handle(WebhookRequest request) {
                seen.set(request);
                return WebhookResponse.text(200, request.queryParameter("hub.challenge"));
            }
        };
        NotificationProperties properties = new NotificationProperties();
        properties.getWebhooks().getTwilio().setEnabled(true);
        WebhookDispatcher dispatcher = new WebhookDispatcher(
                List.of(echo, new TwilioWebhookHandler(properties)), List.of(), Optional.empty());
        mockMvc = MockMvcBuilders.standaloneSetup(new WebhookController(dispatcher)).build();
    }

    @Test
    void get_handlerRoot_passesQueryAndHeaders_andWritesPlainText() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks/echo?hub.mode=subscribe&hub.challenge=12345")
                        .header("X-Custom", "v"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
                .andExpect(content().string("12345"));

        WebhookRequest request = seen.get();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.subPath()).isEmpty();
        assertThat(request.queryParameter("hub.mode")).isEqualTo("subscribe");
        assertThat(request.header("x-custom")).isEqualTo("v");
        assertThat(request.body()).isEmpty();
        assertThat(request.requestUrl())
                .isEqualTo("http://localhost/api/v1/webhooks/echo?hub.mode=subscribe&hub.challenge=12345");
    }

    @Test
    void post_nestedSubPath_passesTheRawBodyBytes() throws Exception {
        byte[] raw = "{\"a\": 1,  \"b\":\"\\u00e9\"}".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/v1/webhooks/echo/a/b").contentType("application/json").content(raw))
                .andExpect(status().isOk());

        assertThat(seen.get().subPath()).isEqualTo("a/b");
        assertThat(seen.get().body()).isEqualTo(raw);
    }

    @Test
    void unknownHandler_is404() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/nobody/status").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void twilio_wrongMethodOrPath_isRejectedAsBefore() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks/twilio/status"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/api/v1/webhooks/twilio/other")
                        .contentType("application/x-www-form-urlencoded").content("MessageSid=SM1"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/webhooks/twilio/status")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void queryParameters_areDecoded_andRepeatedNamesKept() {
        assertThat(WebhookController.queryParameters("a=1&a=2&b=x%20y&flag&&"))
                .containsEntry("a", List.of("1", "2"))
                .containsEntry("b", List.of("x y"))
                .containsEntry("flag", List.of(""));
        assertThat(WebhookController.queryParameters(null)).isEmpty();
    }
}
