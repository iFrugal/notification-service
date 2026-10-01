package com.lazydevs.notification.core.config;

import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.core.metrics.NotificationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

import java.util.Optional;

/**
 * Registers {@link NotificationMetrics} when Micrometer is on the classpath
 * and the application has a {@link MeterRegistry} bean.
 *
 * <p>Ordered after Boot's composite registry auto-configuration, which itself
 * runs after every registry export auto-configuration, so the
 * {@link ConditionalOnBean} check sees Boot-provided registries as well as
 * application-supplied ones.
 * Without a registry bean the send path runs without metrics, because
 * {@code DefaultNotificationService} takes {@code Optional<NotificationMetrics>}.
 */
@AutoConfiguration(afterName = "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration")
@ConditionalOnClass(MeterRegistry.class)
public class NotificationMetricsAutoConfiguration {

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    public NotificationMetrics notificationMetrics(MeterRegistry registry,
                                                   Optional<DeadLetterStore> deadLetterStore,
                                                   Optional<DeliveryEventStore> deliveryEventStore) {
        return new NotificationMetrics(registry, deadLetterStore, deliveryEventStore);
    }
}
