package com.lazydevs.notification.starter;

import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.deadletter.InMemoryDeadLetterStore;
import com.lazydevs.notification.core.delivery.InMemoryDeliveryEventStore;
import com.lazydevs.notification.core.idempotency.CaffeineIdempotencyStore;
import com.lazydevs.notification.core.ratelimit.Bucket4jRateLimiter;
import com.lazydevs.notification.redis.RedisDeadLetterStore;
import com.lazydevs.notification.redis.RedisDeliveryEventStore;
import com.lazydevs.notification.redis.RedisIdempotencyStore;
import com.lazydevs.notification.redis.RedisRateLimiter;
import com.lazydevs.notification.redis.autoconfigure.NotificationRedisAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves {@code notification.store.type} selects the store family of every
 * enabled feature, that the per-feature {@code notification.redis.<feature>.enabled}
 * toggles override it, and that a family whose module is missing fails startup
 * with the Maven coordinates to add.
 *
 * <p>Boot's Redis auto-configuration runs against a mocked
 * {@link LettuceConnectionFactory}, so nothing connects to Redis.
 */
@ExtendWith(OutputCaptureExtension.class)
class StoreTypeAutoConfigurationTest {

    private static final String[] ALL_FEATURES_ENABLED = {
            "notification.idempotency.enabled=true",
            "notification.rate-limit.enabled=true",
            "notification.dead-letter.enabled=true",
            "notification.delivery-events.enabled=true",
    };

    private final WebApplicationContextRunner runner = StarterContextRunners.starterRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
            .withUserConfiguration(MockRedisConnectionConfiguration.class);

    @Test
    void storeTypeAbsent_registersMemoryFamily() {
        runner.withPropertyValues(ALL_FEATURES_ENABLED).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(CaffeineIdempotencyStore.class);
            assertThat(context.getBean(RateLimiter.class)).isInstanceOf(Bucket4jRateLimiter.class);
            assertThat(context.getBean(DeadLetterStore.class)).isInstanceOf(InMemoryDeadLetterStore.class);
            assertThat(context.getBean(DeliveryEventStore.class)).isInstanceOf(InMemoryDeliveryEventStore.class);
        });
    }

    @Test
    void storeTypeRedis_registersRedisStoresForEnabledFeatures() {
        runner.withPropertyValues(ALL_FEATURES_ENABLED)
                .withPropertyValues("notification.store.type=redis")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(IdempotencyStore.class);
                    assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(RedisIdempotencyStore.class);
                    assertThat(context).hasSingleBean(RateLimiter.class);
                    assertThat(context.getBean(RateLimiter.class)).isInstanceOf(RedisRateLimiter.class);
                    assertThat(context).hasSingleBean(DeadLetterStore.class);
                    assertThat(context.getBean(DeadLetterStore.class)).isInstanceOf(RedisDeadLetterStore.class);
                    assertThat(context).hasSingleBean(DeliveryEventStore.class);
                    assertThat(context.getBean(DeliveryEventStore.class)).isInstanceOf(RedisDeliveryEventStore.class);
                });
    }

    @Test
    void storeTypeRedis_disabledFeature_registersNoStore() {
        runner.withPropertyValues("notification.store.type=redis").run(context -> {
            assertThat(context).hasNotFailed();
            // Idempotency is on by default, the other three are off.
            assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(RedisIdempotencyStore.class);
            assertThat(context).doesNotHaveBean(RateLimiter.class);
            assertThat(context).doesNotHaveBean(DeadLetterStore.class);
            assertThat(context).doesNotHaveBean(DeliveryEventStore.class);
        });
    }

    @Test
    void storeTypeRedis_featureFlagFalse_usesMemoryForThatFeature() {
        runner.withPropertyValues(ALL_FEATURES_ENABLED)
                .withPropertyValues("notification.store.type=redis", "notification.redis.rate-limit.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RateLimiter.class)).isInstanceOf(Bucket4jRateLimiter.class);
                    assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(RedisIdempotencyStore.class);
                    assertThat(context.getBean(DeadLetterStore.class)).isInstanceOf(RedisDeadLetterStore.class);
                    assertThat(context.getBean(DeliveryEventStore.class)).isInstanceOf(RedisDeliveryEventStore.class);
                });
    }

    @Test
    void storeTypeMemory_featureFlagTrue_usesRedisForThatFeature() {
        runner.withPropertyValues(ALL_FEATURES_ENABLED)
                .withPropertyValues("notification.store.type=memory", "notification.redis.dead-letter.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DeadLetterStore.class)).isInstanceOf(RedisDeadLetterStore.class);
                    assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(CaffeineIdempotencyStore.class);
                    assertThat(context.getBean(RateLimiter.class)).isInstanceOf(Bucket4jRateLimiter.class);
                    assertThat(context.getBean(DeliveryEventStore.class)).isInstanceOf(InMemoryDeliveryEventStore.class);
                });
    }

    @Test
    void storeTypeRedis_withoutRedisModule_failsNamingArtifact() {
        StarterContextRunners.starterRunnerWithout(NotificationRedisAutoConfiguration.class)
                .withClassLoader(new FilteredClassLoader(NotificationRedisAutoConfiguration.class))
                .withPropertyValues("notification.store.type=redis")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .isInstanceOf(InvalidConfigurationPropertyValueException.class)
                            .hasMessageContaining("Property notification.store.type with value 'redis' is invalid")
                            .hasMessageContaining("Store family 'redis' is selected for idempotency, but "
                                    + "notification-redis is not on the classpath. Add the Maven dependency "
                                    + "com.github.ifrugal:notification-redis (same version as notification-core).");
                });
    }

    @Test
    void storeTypeJdbc_withoutJdbcModule_failsNamingArtifact() {
        runner.withPropertyValues("notification.store.type=jdbc").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context).getFailure()
                    .isInstanceOf(InvalidConfigurationPropertyValueException.class)
                    .hasMessageContaining("Store family 'jdbc' is selected for idempotency, but "
                            + "notification-store-jdbc is not on the classpath. Add the Maven dependency "
                            + "com.github.ifrugal:notification-store-jdbc (same version as notification-core).");
        });
    }

    @Test
    void storeTypeJdbc_everyFeatureDisabled_startsWithoutJdbcModule() {
        runner.withPropertyValues("notification.store.type=jdbc", "notification.idempotency.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(IdempotencyStore.class);
                });
    }

    @Test
    void userStoreBean_winsOverRedis() {
        runner.withUserConfiguration(UserIdempotencyStoreConfiguration.class)
                .withPropertyValues("notification.store.type=redis")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(IdempotencyStore.class);
                    assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(UserIdempotencyStore.class);
                    assertThat(context).doesNotHaveBean(RedisIdempotencyStore.class);
                });
    }

    @Test
    void legacyRedisEnabledFlag_logsWarning(CapturedOutput output) {
        runner.withPropertyValues("notification.redis.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            // Never read: the flag alone does not switch any feature to Redis.
            assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(CaffeineIdempotencyStore.class);
        });
        assertThat(output).contains("notification.redis.enabled is no longer read; use notification.store.type=redis");
    }

    @Configuration(proxyBeanMethods = false)
    static class MockRedisConnectionConfiguration {
        @Bean
        LettuceConnectionFactory redisConnectionFactory() {
            return mock(LettuceConnectionFactory.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserIdempotencyStoreConfiguration {
        @Bean
        IdempotencyStore userIdempotencyStore() {
            return new UserIdempotencyStore();
        }
    }

    static class UserIdempotencyStore implements IdempotencyStore {
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
            // Test stub: nothing to record.
        }
    }
}
