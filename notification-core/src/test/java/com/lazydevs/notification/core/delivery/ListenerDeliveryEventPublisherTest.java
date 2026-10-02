package com.lazydevs.notification.core.delivery;

import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventListener;
import com.lazydevs.notification.api.delivery.DeliveryEvents;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.core.metrics.NotificationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.Ordered;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ListenerDeliveryEventPublisherTest {

    private static final DeliveryEvent EVENT = new DeliveryEvent(Instant.now(), "fcm", "fcm-local:1", "evt-1",
            DeliveryStatus.BOUNCED, DeliveryEvents.REASON_INVALID_TARGET,
            Map.of(DeliveryEvents.ATTR_TARGET_TYPE, DeliveryEvents.TARGET_TYPE_TOKEN));

    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();

    @Test
    void listenersRegisteredAfterConstruction_receiveEvents_inOrder() {
        ListenerDeliveryEventPublisher publisher = new ListenerDeliveryEventPublisher(
                beans.getBeanProvider(DeliveryEventListener.class), Optional.empty());
        List<String> calls = new ArrayList<>();
        beans.registerSingleton("second", new OrderedListener(2, e -> calls.add("second:" + e.providerEventId())));
        beans.registerSingleton("first", new OrderedListener(1, e -> calls.add("first:" + e.providerEventId())));

        publisher.publish(EVENT);

        assertThat(calls).containsExactly("first:evt-1", "second:evt-1");
    }

    @Test
    void aThrowingListener_isSkipped_andTheOthersStillRun() {
        List<DeliveryEvent> received = new ArrayList<>();
        beans.registerSingleton("broken", new OrderedListener(1, e -> {
            throw new IllegalStateException("store down for user@example.com");
        }));
        beans.registerSingleton("working", new OrderedListener(2, received::add));
        ListenerDeliveryEventPublisher publisher = new ListenerDeliveryEventPublisher(
                beans.getBeanProvider(DeliveryEventListener.class), Optional.empty());

        assertThatCode(() -> publisher.publish(EVENT)).doesNotThrowAnyException();
        assertThat(received).containsExactly(EVENT);
    }

    @Test
    void noListeners_andNullEvents_areFine() {
        ListenerDeliveryEventPublisher publisher = new ListenerDeliveryEventPublisher(
                beans.getBeanProvider(DeliveryEventListener.class), Optional.empty());

        assertThatCode(() -> {
            publisher.publish(EVENT);
            publisher.publish(null);
        }).doesNotThrowAnyException();
    }

    @Test
    void eachPublishedEvent_isCountedByProviderAndStatus() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationMetrics metrics = new NotificationMetrics(registry, Optional.empty(), Optional.empty());
        ListenerDeliveryEventPublisher publisher = new ListenerDeliveryEventPublisher(
                beans.getBeanProvider(DeliveryEventListener.class), Optional.of(metrics));

        publisher.publish(EVENT);
        publisher.publish(EVENT);

        assertThat(registry.get("notification.delivery.events.emitted")
                .tag("provider", "fcm").tag("status", "BOUNCED").counter().count()).isEqualTo(2.0);
    }

    private record OrderedListener(int order, DeliveryEventListener delegate)
            implements DeliveryEventListener, Ordered {

        @Override
        public void onEvent(DeliveryEvent event) {
            delegate.onEvent(event);
        }

        @Override
        public int getOrder() {
            return order;
        }
    }
}
