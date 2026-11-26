package com.lazydevs.notification.api.webhook;

import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.exception.TemplateValidationException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookRequestResponseTest {

    @Test
    void request_normalisesMethodAndSubPath_andLooksUpHeadersCaseInsensitively() {
        WebhookRequest request = new WebhookRequest("post", "/status/",
                Map.of("X-Hub-Signature-256", List.of("sha256=abc")),
                Map.of("hub.mode", List.of("subscribe")),
                "{}".getBytes(StandardCharsets.UTF_8), "https://example.com/webhooks/meta");

        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.isMethod("post")).isTrue();
        assertThat(request.subPath()).isEqualTo("status");
        assertThat(request.header("x-hub-signature-256")).isEqualTo("sha256=abc");
        assertThat(request.header("missing")).isNull();
        assertThat(request.queryParameter("hub.mode")).isEqualTo("subscribe");
        assertThat(request.bodyAsString()).isEqualTo("{}");
    }

    @Test
    void request_copiesTheBody_andDefaultsNulls() {
        byte[] raw = {1, 2, 3};
        WebhookRequest request = new WebhookRequest("GET", null, null, null, raw, null);
        raw[0] = 9;

        assertThat(request.body()).containsExactly(1, 2, 3);
        assertThat(request.subPath()).isEmpty();
        assertThat(request.headers()).isEmpty();
        assertThat(new WebhookRequest("GET", "", null, null, null, null).body()).isEmpty();
    }

    @Test
    void request_equalityIsByContent_andToStringHidesValues() {
        WebhookRequest a = new WebhookRequest("POST", "x", Map.of("Authorization", List.of("secret-value")),
                Map.of("token", List.of("query-secret")), "body-secret".getBytes(StandardCharsets.UTF_8), null);
        WebhookRequest b = new WebhookRequest("POST", "x", Map.of("Authorization", List.of("secret-value")),
                Map.of("token", List.of("query-secret")), "body-secret".getBytes(StandardCharsets.UTF_8), null);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).doesNotContain("secret-value", "query-secret", "body-secret")
                .contains("Authorization", "byte[11]");
    }

    @Test
    void response_factories() {
        DeliveryEvent event = new DeliveryEvent(Instant.EPOCH, "p", "m", "e", DeliveryStatus.READ, null, null);
        List<DeliveryEvent> events = new ArrayList<>(List.of(event));
        WebhookResponse ok = WebhookResponse.json(200, "{}", events);
        events.clear();

        assertThat(ok.events()).containsExactly(event);
        assertThat(ok.contentType()).isEqualTo(WebhookResponse.APPLICATION_JSON);
        assertThat(ok.signatureRejected()).isFalse();
        assertThat(WebhookResponse.json(404, "{}").events()).isEmpty();
        assertThat(WebhookResponse.text(200, "challenge").contentType()).isEqualTo(WebhookResponse.TEXT_PLAIN);
        WebhookResponse rejected = WebhookResponse.signatureRejected(null, null);
        assertThat(rejected.status()).isEqualTo(403);
        assertThat(rejected.signatureRejected()).isTrue();
        assertThat(new WebhookResponse(200, null, null, null, false).events()).isEmpty();
    }

    @Test
    void templateValidationException_isAClientError() {
        TemplateValidationException e = new TemplateValidationException("bad");
        assertThat(e.getErrorCode()).isEqualTo(TemplateValidationException.ERROR_CODE);
        assertThat(e.getStatusCode()).isEqualTo(400);
    }
}
