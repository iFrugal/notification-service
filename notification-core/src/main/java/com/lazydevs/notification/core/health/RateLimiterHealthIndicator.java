package com.lazydevs.notification.core.health;

import com.lazydevs.notification.api.ratelimit.RateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator health indicator for the {@link RateLimiter} (DD-21).
 * Registered as {@code /actuator/health/rateLimit}.
 *
 * <p>The SPI surface is just {@code tryConsume(key)} — no probe-able
 * state. We report the bean's presence and impl class. Bucket-count
 * detail is operationally interesting but more naturally a metric
 * (DD-22) than a health field.
 */
@Component("rateLimit")
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "notification.rate-limit", name = "enabled", havingValue = "true")
public class RateLimiterHealthIndicator implements HealthIndicator {

    private final RateLimiter rateLimiter;

    public RateLimiterHealthIndicator(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("enabled", true)
                .withDetail("impl", rateLimiter.getClass().getSimpleName())
                .build();
    }
}
