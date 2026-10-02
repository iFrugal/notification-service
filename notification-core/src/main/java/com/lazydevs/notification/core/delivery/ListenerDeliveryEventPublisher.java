package com.lazydevs.notification.core.delivery;

import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventListener;
import com.lazydevs.notification.api.delivery.DeliveryEventPublisher;
import com.lazydevs.notification.api.util.PiiMasking;
import com.lazydevs.notification.core.metrics.NotificationMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Objects;
import java.util.Optional;

/**
 * Default {@link DeliveryEventPublisher} (DD-25): hands provider-originated
 * events to every {@link DeliveryEventListener} bean, the same listeners the
 * webhook controller feeds, including a configured
 * {@link com.lazydevs.notification.api.delivery.DeliveryEventStore}.
 *
 * <p>Listeners are looked up on every publish, in
 * {@link ObjectProvider#orderedStream() order}, rather than at construction:
 * the provider registry receives this publisher while it initialises
 * providers, and a listener may itself depend on beans created later.
 * Publishing is rare (an invalid push token, for example), so the lookup cost
 * does not matter.
 *
 * <p>Runs on the caller's thread, so a listener sees the caller's tenant
 * context. A listener that throws is logged and skipped; the others still run
 * and the send is not affected. Each published event increments
 * {@code notification.delivery.events.emitted{provider, status}} when
 * metrics are available.
 */
@Slf4j
public class ListenerDeliveryEventPublisher implements DeliveryEventPublisher {

    private final ObjectProvider<DeliveryEventListener> listeners;
    private final Optional<NotificationMetrics> metrics;

    public ListenerDeliveryEventPublisher(ObjectProvider<DeliveryEventListener> listeners,
                                          Optional<NotificationMetrics> metrics) {
        this.listeners = Objects.requireNonNull(listeners, "listeners");
        this.metrics = metrics == null ? Optional.empty() : metrics;
    }

    @Override
    public void publish(DeliveryEvent event) {
        if (event == null) {
            return;
        }
        metrics.ifPresent(m -> m.recordDeliveryEventEmitted(event.providerName(), event.status()));
        listeners.orderedStream().forEach(listener -> deliver(listener, event));
    }

    private static void deliver(DeliveryEventListener listener, DeliveryEvent event) {
        try {
            listener.onEvent(event);
        } catch (RuntimeException e) {
            // The listener contract says "must not throw"; a provider's send
            // must not fail because a listener broke it.
            log.warn("DeliveryEventListener {} threw on a {} event from {}, continuing: {}",
                    listener.getClass().getSimpleName(), event.status(), event.providerName(),
                    PiiMasking.redact(e.toString()));
        }
    }
}
