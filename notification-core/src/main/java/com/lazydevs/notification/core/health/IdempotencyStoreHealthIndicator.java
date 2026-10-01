package com.lazydevs.notification.core.health;

import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Actuator health indicator for the {@link IdempotencyStore} (DD-21).
 * Registered as {@code /actuator/health/idempotency}.
 *
 * <p>The idempotency SPI doesn't expose a {@code size()} probe (its
 * surface is intentionally narrow — find / mark in-progress /
 * mark complete / evict expired). So the indicator just confirms the
 * bean is wired and reports the implementation class so operators
 * can tell at a glance which backend is in use.
 *
 * <p>The deeper "is Redis reachable?" question is answered by Spring
 * Data Redis's own health indicator at {@code /actuator/health/redis};
 * we don't duplicate that probe here.
 */
public class IdempotencyStoreHealthIndicator implements HealthIndicator {

    private final IdempotencyStore store;

    public IdempotencyStoreHealthIndicator(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("enabled", true)
                .withDetail("impl", store.getClass().getSimpleName())
                .build();
    }
}
