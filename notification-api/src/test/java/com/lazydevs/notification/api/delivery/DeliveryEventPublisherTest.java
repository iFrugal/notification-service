package com.lazydevs.notification.api.delivery;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DeliveryEventPublisherTest {

    private static final DeliveryEvent INVALID_TARGET = new DeliveryEvent(Instant.now(), "fcm", "fcm-local:1",
            "evt-1", DeliveryStatus.BOUNCED, DeliveryEvents.REASON_INVALID_TARGET,
            Map.of(DeliveryEvents.ATTR_FAILURE, "UNREGISTERED",
                    DeliveryEvents.ATTR_ERROR_CODE, "FCM_UNREGISTERED",
                    DeliveryEvents.ATTR_TARGET_TYPE, DeliveryEvents.TARGET_TYPE_TOKEN,
                    DeliveryEvents.ATTR_TOKEN_HASH, "sha256:0123456789abcdef"));

    @Test
    void noOpDropsEventsWithoutThrowing() {
        assertThatCode(() -> DeliveryEventPublisher.NO_OP.publish(INVALID_TARGET)).doesNotThrowAnyException();
    }

    @Test
    void anEmitterPublishesThroughTheInjectedPublisher() {
        List<DeliveryEvent> received = new ArrayList<>();
        StubEmitter emitter = new StubEmitter();

        emitter.send();
        emitter.setDeliveryEventPublisher(received::add);
        emitter.send();

        assertThat(received).containsExactly(INVALID_TARGET);
    }

    @Test
    void vocabularyValuesAreStable() {
        // Listeners and stored rows match on these strings; renaming one is a breaking change.
        assertThat(DeliveryEvents.REASON_INVALID_TARGET).isEqualTo("INVALID_TARGET");
        assertThat(List.of(DeliveryEvents.ATTR_FAILURE, DeliveryEvents.ATTR_ERROR_CODE,
                DeliveryEvents.ATTR_TARGET_TYPE, DeliveryEvents.ATTR_TOKEN_HASH))
                .containsExactly("failure", "errorCode", "targetType", "tokenHash");
        assertThat(List.of(DeliveryEvents.TARGET_TYPE_TOKEN, DeliveryEvents.TARGET_TYPE_FID))
                .containsExactly("token", "fid");
    }

    private static final class StubEmitter implements DeliveryEventEmitter {
        private DeliveryEventPublisher publisher = DeliveryEventPublisher.NO_OP;

        @Override
        public void setDeliveryEventPublisher(DeliveryEventPublisher publisher) {
            this.publisher = publisher;
        }

        void send() {
            publisher.publish(INVALID_TARGET);
        }
    }
}
