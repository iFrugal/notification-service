package com.lazydevs.notification.starter;

import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.deadletter.InMemoryDeadLetterStore;
import com.lazydevs.notification.core.delivery.InMemoryDeliveryEventStore;
import com.lazydevs.notification.core.health.DeadLetterStoreHealthIndicator;
import com.lazydevs.notification.core.idempotency.CaffeineIdempotencyStore;
import com.lazydevs.notification.core.ratelimit.Bucket4jRateLimiter;
import com.lazydevs.notification.redis.RedisDeadLetterStore;
import com.lazydevs.notification.redis.RedisDeliveryEventStore;
import com.lazydevs.notification.redis.RedisIdempotencyStore;
import com.lazydevs.notification.redis.RedisRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves the Redis-backed stores activate in starter mode when their flags are
 * set, and that the in-memory defaults then step aside.
 *
 * <p>Boot's own Redis auto-configuration runs against a mocked
 * {@link LettuceConnectionFactory}, so its {@link StringRedisTemplate} is
 * built as in a real application but nothing connects to Redis.
 */
class RedisStarterWiringTest {

    private static final String[] ALL_FEATURES_ENABLED = {
            "notification.idempotency.enabled=true",
            "notification.rate-limit.enabled=true",
            "notification.dead-letter.enabled=true",
            "notification.delivery-events.enabled=true",
    };

    private final WebApplicationContextRunner runner = StarterContextRunners.starterRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
            .withUserConfiguration(MockRedisConnectionConfiguration.class)
            .withPropertyValues(ALL_FEATURES_ENABLED);

    @Test
    void redisIdempotencyEnabled_replacesCaffeineStore() {
        runner.withPropertyValues("notification.redis.idempotency.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(StringRedisTemplate.class);
            assertThat(context).hasSingleBean(IdempotencyStore.class);
            assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(RedisIdempotencyStore.class);
            assertThat(context).doesNotHaveBean(CaffeineIdempotencyStore.class);
        });
    }

    @Test
    void redisDeadLetterEnabled_replacesInMemoryStoreAndFeedsHealthIndicator() {
        runner.withPropertyValues("notification.redis.dead-letter.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DeadLetterStore.class);
            assertThat(context.getBean(DeadLetterStore.class)).isInstanceOf(RedisDeadLetterStore.class);
            assertThat(context).doesNotHaveBean(InMemoryDeadLetterStore.class);
            assertThat(context.getBean("dlq")).isInstanceOf(DeadLetterStoreHealthIndicator.class);
        });
    }

    @Test
    void redisDeliveryEventsEnabled_replacesInMemoryStore() {
        runner.withPropertyValues("notification.redis.delivery-events.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DeliveryEventStore.class);
            assertThat(context.getBean(DeliveryEventStore.class)).isInstanceOf(RedisDeliveryEventStore.class);
            assertThat(context).doesNotHaveBean(InMemoryDeliveryEventStore.class);
        });
    }

    @Test
    void redisRateLimitEnabled_replacesBucket4jLimiter() {
        runner.withPropertyValues("notification.redis.rate-limit.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RateLimiter.class);
            assertThat(context.getBean(RateLimiter.class)).isInstanceOf(RedisRateLimiter.class);
            assertThat(context).doesNotHaveBean(Bucket4jRateLimiter.class);
        });
    }

    @Test
    void redisFlagsUnset_keepInMemoryDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(CaffeineIdempotencyStore.class);
            assertThat(context.getBean(DeadLetterStore.class)).isInstanceOf(InMemoryDeadLetterStore.class);
            assertThat(context.getBean(DeliveryEventStore.class)).isInstanceOf(InMemoryDeliveryEventStore.class);
            assertThat(context.getBean(RateLimiter.class)).isInstanceOf(Bucket4jRateLimiter.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class MockRedisConnectionConfiguration {
        @Bean
        LettuceConnectionFactory redisConnectionFactory() {
            return mock(LettuceConnectionFactory.class);
        }
    }
}
