package com.lazydevs.notification.core.config;

import com.lazydevs.notification.api.audit.NotificationAuditStore;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.api.retry.RetryPredicate;
import com.lazydevs.notification.core.audit.InMemoryNotificationAuditStore;
import com.lazydevs.notification.core.deadletter.InMemoryDeadLetterStore;
import com.lazydevs.notification.core.delivery.InMemoryDeliveryEventStore;
import com.lazydevs.notification.core.idempotency.CaffeineIdempotencyStore;
import com.lazydevs.notification.core.ratelimit.Bucket4jRateLimiter;
import com.lazydevs.notification.core.retry.RetryExecutor;
import com.lazydevs.notification.core.store.ConditionalOnStoreType;
import com.lazydevs.notification.core.store.StoreFeature;
import com.lazydevs.notification.core.store.StoreType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Registers the replaceable default SPI implementations of notification-core.
 *
 * <p>These defaults must be {@code @Bean} methods in an auto-configuration,
 * not component-scanned {@code @Component} classes.
 * A scanned class annotated {@code @ConditionalOnMissingBean} of its own
 * type sees its own bean definition when the condition is evaluated and
 * removes itself, so the default never registers.
 * Auto-configurations are processed after the application's own
 * configuration, so {@link ConditionalOnMissingBean} here reliably lets an
 * application-supplied bean (for example a Redis-backed store) win.
 *
 * <p>The in-memory stores deliberately carry no
 * {@code @ConditionalOnStoreType(feature, MEMORY)}: their feature flag and
 * {@link ConditionalOnMissingBean} are enough.
 * The Redis and JDBC store auto-configurations are ordered before this
 * class, so when a feature resolves to one of those families its store is
 * already registered and the in-memory default backs off.
 * The only way a non-memory family could leave the in-memory default in
 * place is its module being absent, and
 * {@link com.lazydevs.notification.core.store.StoreTypeValidator} fails
 * startup in exactly that case, so the gap is closed.
 *
 * <p>Listed in {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports},
 * which both the starter and the standalone server rely on.
 * Bean names match the class-derived names the scanned components used to have.
 */
@AutoConfiguration
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationCoreDefaultsAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "notification.idempotency", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public CaffeineIdempotencyStore caffeineIdempotencyStore(NotificationProperties properties) {
        return new CaffeineIdempotencyStore(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "notification.dead-letter", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(DeadLetterStore.class)
    public InMemoryDeadLetterStore inMemoryDeadLetterStore(NotificationProperties properties) {
        return new InMemoryDeadLetterStore(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "notification.delivery-events", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(DeliveryEventStore.class)
    public InMemoryDeliveryEventStore inMemoryDeliveryEventStore(NotificationProperties properties) {
        return new InMemoryDeliveryEventStore(properties);
    }

    /**
     * In-memory audit store (DD-33). Unlike the other in-memory defaults it
     * carries {@link ConditionalOnStoreType}: there is no Redis audit store,
     * so with the Redis family it must not register silently in its place.
     */
    @Bean
    @ConditionalOnStoreType(feature = StoreFeature.AUDIT, type = StoreType.MEMORY)
    @ConditionalOnMissingBean(NotificationAuditStore.class)
    public InMemoryNotificationAuditStore inMemoryNotificationAuditStore(NotificationProperties properties) {
        return new InMemoryNotificationAuditStore(properties.getAudit().getMaxEntries(),
                Duration.ofDays(properties.getAudit().getRetentionDays()), Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(prefix = "notification.rate-limit", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(RateLimiter.class)
    public Bucket4jRateLimiter bucket4jRateLimiter(NotificationProperties properties) {
        return new Bucket4jRateLimiter(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "notification.retry", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(RetryExecutor.class)
    public RetryExecutor retryExecutor(NotificationProperties properties,
                                       Optional<RetryPredicate> customPredicate) {
        return new RetryExecutor(properties, customPredicate);
    }
}
