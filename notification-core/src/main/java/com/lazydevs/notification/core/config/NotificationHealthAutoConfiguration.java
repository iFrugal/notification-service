package com.lazydevs.notification.core.config;

import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.health.DeadLetterStoreHealthIndicator;
import com.lazydevs.notification.core.health.DeliveryEventStoreHealthIndicator;
import com.lazydevs.notification.core.health.IdempotencyStoreHealthIndicator;
import com.lazydevs.notification.core.health.RateLimiterHealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

/**
 * Registers the DD-21 health indicators, one for each store or limiter that
 * is actually present in the context.
 *
 * <p>Each indicator is gated on its SPI bean rather than on a feature flag,
 * so it follows whichever implementation won: the in-memory default, the
 * Redis-backed one, or an application-supplied bean.
 * Ordered after the auto-configurations that register those beans so the
 * {@link ConditionalOnBean} checks see them.
 * Bean names are the indicator names under {@code /actuator/health}.
 */
@AutoConfiguration(after = NotificationCoreDefaultsAutoConfiguration.class,
        afterName = "com.lazydevs.notification.redis.autoconfigure.NotificationRedisAutoConfiguration")
@ConditionalOnClass(HealthIndicator.class)
public class NotificationHealthAutoConfiguration {

    @Bean("dlq")
    @ConditionalOnBean(DeadLetterStore.class)
    public DeadLetterStoreHealthIndicator deadLetterStoreHealthIndicator(DeadLetterStore store,
                                                                         NotificationProperties properties) {
        return new DeadLetterStoreHealthIndicator(store, properties);
    }

    @Bean("deliveryEvents")
    @ConditionalOnBean(DeliveryEventStore.class)
    public DeliveryEventStoreHealthIndicator deliveryEventStoreHealthIndicator(DeliveryEventStore store,
                                                                               NotificationProperties properties) {
        return new DeliveryEventStoreHealthIndicator(store, properties);
    }

    @Bean("idempotency")
    @ConditionalOnBean(IdempotencyStore.class)
    public IdempotencyStoreHealthIndicator idempotencyStoreHealthIndicator(IdempotencyStore store) {
        return new IdempotencyStoreHealthIndicator(store);
    }

    @Bean("rateLimit")
    @ConditionalOnBean(RateLimiter.class)
    public RateLimiterHealthIndicator rateLimiterHealthIndicator(RateLimiter rateLimiter) {
        return new RateLimiterHealthIndicator(rateLimiter);
    }
}
