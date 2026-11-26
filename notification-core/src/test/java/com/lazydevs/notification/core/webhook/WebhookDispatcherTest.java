package com.lazydevs.notification.core.webhook;

import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventListener;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.webhook.WebhookHandler;
import com.lazydevs.notification.api.webhook.WebhookRequest;
import com.lazydevs.notification.api.webhook.WebhookResponse;
import com.lazydevs.notification.core.metrics.NotificationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookDispatcherTest {

    private static final WebhookRequest POST = new WebhookRequest("POST", "status", null, null, new byte[0], null);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Optional<NotificationMetrics> metrics =
            Optional.of(new NotificationMetrics(registry, Optional.empty(), Optional.empty()));
    private final List<DeliveryEvent> received = new ArrayList<>();
    private final DeliveryEventListener recording = received::add;

    private static DeliveryEvent event(String messageId, DeliveryStatus status) {
        return new DeliveryEvent(Instant.EPOCH, "stub", messageId, messageId + ":" + status, status, null, null);
    }

    private static WebhookHandler handler(String id, Function<WebhookRequest, WebhookResponse> body) {
        return new WebhookHandler() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public WebhookResponse handle(WebhookRequest request) {
                return body.apply(request);
            }
        };
    }

    @Test
    void routesByHandlerId_publishesEvents_andCountsThem() {
        WebhookHandler stub = handler("stub", r -> WebhookResponse.json(200, "{\"status\":\"accepted\"}",
                List.of(event("m1", DeliveryStatus.SENT), event("m1", DeliveryStatus.READ))));
        WebhookDispatcher dispatcher = new WebhookDispatcher(List.of(stub), List.of(recording), metrics);

        WebhookResponse response = dispatcher.dispatch("stub", POST);

        assertThat(response.status()).isEqualTo(200);
        assertThat(received).extracting(DeliveryEvent::status)
                .containsExactly(DeliveryStatus.SENT, DeliveryStatus.READ);
        assertThat(registry.counter("notification.delivery-events.received.total",
                "provider", "stub", "status", "READ").count()).isEqualTo(1.0);
        assertThat(dispatcher.handlerIds()).containsExactly("stub");
    }

    @Test
    void unknownHandler_is404_andNothingIsPublished() {
        WebhookDispatcher dispatcher = new WebhookDispatcher(List.of(), List.of(recording), metrics);

        WebhookResponse response = dispatcher.dispatch("nobody", POST);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.contentType()).isEqualTo(WebhookResponse.APPLICATION_JSON);
        assertThat(dispatcher.dispatch(null, POST).status()).isEqualTo(404);
        assertThat(received).isEmpty();
    }

    @Test
    void signatureRejection_isCountedUnderTheHandlerId() {
        WebhookHandler stub = handler("stub", r -> WebhookResponse.signatureRejected(null, null));
        WebhookDispatcher dispatcher = new WebhookDispatcher(List.of(stub), List.of(recording), metrics);

        assertThat(dispatcher.dispatch("stub", POST).status()).isEqualTo(403);
        assertThat(registry.counter("notification.webhook.signature.failed.total", "provider", "stub").count())
                .isEqualTo(1.0);
        assertThat(received).isEmpty();
    }

    @Test
    void throwingListener_doesNotStopTheOthers_norChangeTheAnswer() {
        WebhookHandler stub = handler("stub", r -> WebhookResponse.json(200, "{}",
                List.of(event("m1", DeliveryStatus.DELIVERED))));
        DeliveryEventListener throwing = e -> {
            throw new IllegalStateException("listener broke");
        };
        WebhookDispatcher dispatcher = new WebhookDispatcher(List.of(stub), List.of(throwing, recording),
                Optional.empty());

        assertThat(dispatcher.dispatch("stub", POST).status()).isEqualTo(200);
        assertThat(received).hasSize(1);
    }

    @Test
    void throwingOrNullHandler_is500() {
        WebhookHandler throwing = handler("boom", r -> {
            throw new IllegalStateException("handler broke");
        });
        WebhookHandler silent = handler("silent", r -> null);
        WebhookDispatcher dispatcher = new WebhookDispatcher(List.of(throwing, silent), List.of(recording),
                Optional.empty());

        assertThat(dispatcher.dispatch("boom", POST).status()).isEqualTo(500);
        assertThat(dispatcher.dispatch("silent", POST).status()).isEqualTo(500);
    }

    @Test
    void listenersAreReadOnEveryDispatch() {
        List<DeliveryEventListener> late = new ArrayList<>();
        WebhookHandler stub = handler("stub", r -> WebhookResponse.json(200, "{}",
                List.of(event("m1", DeliveryStatus.DELIVERED))));
        WebhookDispatcher dispatcher = new WebhookDispatcher(List.of(stub), late, Optional.empty());
        late.add(recording);

        dispatcher.dispatch("stub", POST);

        assertThat(received).hasSize(1);
    }

    @Test
    void duplicateOrMalformedIds_failConstruction() {
        WebhookHandler a = handler("same", r -> WebhookResponse.json(200, "{}"));
        WebhookHandler b = handler("same", r -> WebhookResponse.json(200, "{}"));
        List<WebhookHandler> duplicates = List.of(a, b);
        List<WebhookHandler> malformed = List.of(handler("Bad/Id", r -> WebhookResponse.json(200, "{}")));
        List<DeliveryEventListener> none = List.of();
        Optional<NotificationMetrics> noMetrics = Optional.empty();

        assertThatThrownBy(() -> new WebhookDispatcher(duplicates, none, noMetrics))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'same'");
        assertThatThrownBy(() -> new WebhookDispatcher(malformed, none, noMetrics))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Bad/Id");
        assertThat(new WebhookDispatcher(null, none, noMetrics).handlerIds()).isEmpty();
    }
}
