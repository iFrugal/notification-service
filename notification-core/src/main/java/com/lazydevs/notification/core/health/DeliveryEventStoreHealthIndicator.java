package com.lazydevs.notification.core.health;

import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.core.config.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Actuator health indicator for the {@link DeliveryEventStore}
 * (DD-21). Registered as {@code /actuator/health/deliveryEvents}.
 *
 * <p>Reports {@code UP} with current size + configured cap. Unlike
 * the DLQ indicator there's no "near-full" alert — delivery events
 * are a high-rate, naturally-cycling stream; them filling the buffer
 * is the expected steady-state behaviour, not an alert condition.
 */
@Slf4j
@Component("deliveryEvents")
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "notification.delivery-events", name = "enabled", havingValue = "true")
public class DeliveryEventStoreHealthIndicator implements HealthIndicator {

    private final DeliveryEventStore store;
    private final int maxEntries;

    public DeliveryEventStoreHealthIndicator(DeliveryEventStore store,
                                             NotificationProperties properties) {
        this.store = store;
        this.maxEntries = properties.getDeliveryEvents().getMaxEntries();
    }

    @Override
    public Health health() {
        int size;
        try {
            size = store.size();
        } catch (RuntimeException e) {
            log.warn("Delivery-event store size probe failed: {}", e.toString());
            return Health.unknown()
                    .withDetail("error", "size probe failed: " + e.getClass().getSimpleName())
                    .build();
        }
        Health.Builder builder = Health.up().withDetail("maxEntries", maxEntries);
        if (size < 0) {
            builder.withDetail("size", "unavailable");
        } else {
            builder.withDetail("size", size);
        }
        return builder.build();
    }
}
