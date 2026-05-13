package com.lazydevs.notification.core.health;

import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.core.config.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Actuator health indicator for the DLQ (DD-21). Registered as
 * {@code /actuator/health/dlq}.
 *
 * <p>Status flips from {@code UP} to {@code OUT_OF_SERVICE} when the
 * DLQ is at or past the configured near-full threshold (default 80%
 * of {@code max-entries}). Not {@code DOWN} — DD-21 §"Why
 * OUT_OF_SERVICE rather than DOWN": a near-full DLQ is full of
 * information operators need to act on, not broken.
 *
 * <p>Gated on the {@link DeadLetterStore} bean being present; when
 * {@code notification.dead-letter.enabled=false} this indicator never
 * registers and the {@code /actuator/health/dlq} URL returns 404 (the
 * Boot-standard behaviour for unconfigured indicators).
 */
@Slf4j
@Component("dlq")
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "notification.dead-letter", name = "enabled", havingValue = "true")
public class DeadLetterStoreHealthIndicator implements HealthIndicator {

    private final DeadLetterStore store;
    private final int maxEntries;
    private final int nearFullPercent;

    public DeadLetterStoreHealthIndicator(DeadLetterStore store,
                                          NotificationProperties properties) {
        this.store = store;
        this.maxEntries = properties.getDeadLetter().getMaxEntries();
        this.nearFullPercent = properties.getHealth().getDlqNearFullPercent();
    }

    @Override
    public Health health() {
        int size;
        try {
            size = store.size();
        } catch (RuntimeException e) {
            // The store SPI promises never to throw, but a buggy
            // implementation shouldn't crash the health endpoint.
            log.warn("DLQ size probe failed: {}", e.toString());
            return Health.unknown()
                    .withDetail("error", "size probe failed: " + e.getClass().getSimpleName())
                    .build();
        }

        // size() returns -1 from backends that can't answer cheaply.
        // Treat as "we can't tell" — UP, but without a useful fill
        // percent.
        if (size < 0) {
            return Health.up()
                    .withDetail("size", "unavailable")
                    .withDetail("maxEntries", maxEntries)
                    .build();
        }

        int fillPercent = maxEntries == 0 ? 0 : (int) Math.min(100L, size * 100L / maxEntries);
        Health.Builder builder = (nearFullPercent > 0 && fillPercent >= nearFullPercent)
                ? Health.status(Status.OUT_OF_SERVICE)
                : Health.up();
        return builder
                .withDetail("size", size)
                .withDetail("maxEntries", maxEntries)
                .withDetail("fillPercent", fillPercent)
                .withDetail("nearFullPercent", nearFullPercent)
                .build();
    }
}
