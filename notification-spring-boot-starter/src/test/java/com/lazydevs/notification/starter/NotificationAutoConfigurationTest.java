package com.lazydevs.notification.starter;

import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.config.NotificationCoreAutoConfiguration;
import com.lazydevs.notification.core.config.NotificationCoreDefaultsAutoConfiguration;
import com.lazydevs.notification.core.config.NotificationHealthAutoConfiguration;
import com.lazydevs.notification.core.config.NotificationMetricsAutoConfiguration;
import com.lazydevs.notification.core.deadletter.InMemoryDeadLetterStore;
import com.lazydevs.notification.core.delivery.InMemoryDeliveryEventStore;
import com.lazydevs.notification.core.idempotency.CaffeineIdempotencyStore;
import com.lazydevs.notification.core.metrics.NotificationMetrics;
import com.lazydevs.notification.core.ratelimit.Bucket4jRateLimiter;
import com.lazydevs.notification.core.retry.RetryExecutor;
import com.lazydevs.notification.kafka.autoconfigure.NotificationKafkaAutoConfiguration;
import com.lazydevs.notification.redis.autoconfigure.NotificationRedisAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the default SPI implementations register in starter mode and
 * step aside when the application supplies its own bean.
 *
 * <p>A missing default is silent: {@code DefaultNotificationService}
 * takes each SPI as an {@code Optional}, so a default that fails to
 * register simply switches the feature off.
 * These tests are the guard against that.
 */
class NotificationAutoConfigurationTest {

    /** Opt-in features that are off by default in {@code NotificationProperties}. */
    private static final String[] ALL_OPT_IN_FEATURES_ENABLED = {
            "notification.dead-letter.enabled=true",
            "notification.rate-limit.enabled=true",
            "notification.retry.enabled=true",
            "notification.delivery-events.enabled=true",
    };

    private final WebApplicationContextRunner runner = StarterContextRunners.starterRunner();

    @Test
    void importsFiles_listEveryNotificationAutoConfiguration() {
        List<String> candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())
                .getCandidates();
        assertThat(candidates).contains(
                NotificationAutoConfiguration.class.getName(),
                NotificationCoreAutoConfiguration.class.getName(),
                NotificationCoreDefaultsAutoConfiguration.class.getName(),
                NotificationMetricsAutoConfiguration.class.getName(),
                NotificationHealthAutoConfiguration.class.getName(),
                NotificationRedisAutoConfiguration.class.getName(),
                NotificationKafkaAutoConfiguration.class.getName());
    }

    @Test
    void withNoProperties_registersCaffeineIdempotencyStoreAndLeavesOptInFeaturesOff() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(NotificationService.class);
            assertThat(context).hasSingleBean(IdempotencyStore.class);
            assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(CaffeineIdempotencyStore.class);
            assertThat(context).doesNotHaveBean(DeadLetterStore.class);
            assertThat(context).doesNotHaveBean(DeliveryEventStore.class);
            assertThat(context).doesNotHaveBean(RateLimiter.class);
            assertThat(context).doesNotHaveBean(RetryExecutor.class);
        });
    }

    @Test
    void withAllFeaturesEnabled_registersExactlyOneDefaultOfEachSpi() {
        runner.withPropertyValues(ALL_OPT_IN_FEATURES_ENABLED).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IdempotencyStore.class);
            assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(CaffeineIdempotencyStore.class);
            assertThat(context).hasSingleBean(DeadLetterStore.class);
            assertThat(context.getBean(DeadLetterStore.class)).isInstanceOf(InMemoryDeadLetterStore.class);
            assertThat(context).hasSingleBean(DeliveryEventStore.class);
            assertThat(context.getBean(DeliveryEventStore.class)).isInstanceOf(InMemoryDeliveryEventStore.class);
            assertThat(context).hasSingleBean(RateLimiter.class);
            assertThat(context.getBean(RateLimiter.class)).isInstanceOf(Bucket4jRateLimiter.class);
            assertThat(context).hasSingleBean(RetryExecutor.class);
        });
    }

    @Test
    void userIdempotencyStore_replacesTheCaffeineDefault() {
        runner.withUserConfiguration(UserIdempotencyStoreConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IdempotencyStore.class);
            assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(StubIdempotencyStore.class);
            assertThat(context).doesNotHaveBean(CaffeineIdempotencyStore.class);
        });
    }

    @Test
    void componentScannedIdempotencyStore_replacesTheCaffeineDefault() {
        // Same shape as RedisIdempotencyStore: a @Component registered by the
        // application's own configuration, not a @Bean method.
        runner.withUserConfiguration(ScannedIdempotencyStoreConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IdempotencyStore.class);
            assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(ScannedIdempotencyStore.class);
        });
    }

    @Test
    void userDeadLetterStore_replacesTheInMemoryDefault() {
        runner.withPropertyValues("notification.dead-letter.enabled=true")
                .withUserConfiguration(UserDeadLetterStoreConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DeadLetterStore.class);
                    assertThat(context.getBean(DeadLetterStore.class)).isInstanceOf(StubDeadLetterStore.class);
                    assertThat(context).doesNotHaveBean(InMemoryDeadLetterStore.class);
                });
    }

    @Test
    void idempotencyDisabled_registersNoIdempotencyStore() {
        runner.withPropertyValues("notification.idempotency.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(IdempotencyStore.class);
            assertThat(context).hasSingleBean(NotificationService.class);
        });
    }

    @Test
    void userDeliveryEventStore_replacesInMemoryDefault() {
        runner.withPropertyValues("notification.delivery-events.enabled=true")
                .withUserConfiguration(UserDeliveryEventStoreConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DeliveryEventStore.class);
                    assertThat(context.getBean(DeliveryEventStore.class)).isInstanceOf(StubDeliveryEventStore.class);
                    assertThat(context).doesNotHaveBean(InMemoryDeliveryEventStore.class);
                });
    }

    @Test
    void micrometerWithoutRegistryBean_startsWithoutMetrics() {
        // Micrometer is on the test classpath (via actuator), but nothing
        // registers a MeterRegistry bean: the context must still start.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MeterRegistry.class);
            assertThat(context).doesNotHaveBean(NotificationMetrics.class);
            assertThat(context).hasSingleBean(NotificationService.class);
        });
    }

    @Test
    void registryBean_registersMetrics() {
        runner.withUserConfiguration(MeterRegistryConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(NotificationMetrics.class);
            assertThat(context).hasSingleBean(NotificationService.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserDeliveryEventStoreConfiguration {
        @Bean
        DeliveryEventStore userDeliveryEventStore() {
            return new StubDeliveryEventStore();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserIdempotencyStoreConfiguration {
        @Bean
        IdempotencyStore userIdempotencyStore() {
            return new StubIdempotencyStore();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ScannedIdempotencyStore.class)
    static class ScannedIdempotencyStoreConfiguration {
    }

    @Component
    static class ScannedIdempotencyStore extends StubIdempotencyStore {
    }

    @Configuration(proxyBeanMethods = false)
    static class UserDeadLetterStoreConfiguration {
        @Bean
        DeadLetterStore userDeadLetterStore() {
            return new StubDeadLetterStore();
        }
    }

    static class StubIdempotencyStore implements IdempotencyStore {
        @Override
        public Optional<IdempotencyRecord> findExisting(IdempotencyKey key) {
            return Optional.empty();
        }

        @Override
        public boolean markInProgress(IdempotencyKey key, String notificationId) {
            return true;
        }

        @Override
        public void markComplete(IdempotencyKey key, NotificationResponse response) {
            // no-op stub
        }
    }

    static final class StubDeadLetterStore implements DeadLetterStore {
        @Override
        public void add(DeadLetterEntry entry) {
            // no-op stub
        }

        @Override
        public Optional<List<DeadLetterEntry>> snapshot() {
            return Optional.of(List.of());
        }

        @Override
        public int size() {
            return 0;
        }
    }

    static final class StubDeliveryEventStore implements DeliveryEventStore {
        @Override
        public void add(DeliveryEvent event) {
            // no-op stub
        }

        @Override
        public Optional<List<DeliveryEvent>> snapshot() {
            return Optional.of(List.of());
        }

        @Override
        public Optional<List<DeliveryEvent>> findByProviderMessageId(String providerName,
                                                                     String providerMessageId) {
            return Optional.of(List.of());
        }

        @Override
        public int size() {
            return 0;
        }
    }
}
