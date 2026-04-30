package com.lazydevs.notification.redis;

import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring-only smoke test for the DD-14 Redis beans. <strong>Doesn't
 * need Docker</strong> — Spring Data Redis lazy-connects, and the
 * lazy-init in {@link RedisRateLimiter} means the full context loads
 * cleanly against a closed port. We assert beans register correctly
 * without ever talking to Redis.
 *
 * <p>This test catches the class of bug that broke PR #33's first
 * push: a Redis impl whose constructor eagerly opened a Lettuce
 * connection failed to instantiate when Redis was unreachable, and
 * Spring silently dropped it as an autowire candidate. The
 * {@code @Testcontainers(disabledWithoutDocker = true)} skip on the
 * integration tests masked this locally — only CI surfaced it.
 *
 * <p>Adding this test means a future Docker-less run validates wiring
 * even when every {@code *IntegrationTest} skips.
 */
@SpringBootTest(classes = TestRedisApp.class)
@TestPropertySource(properties = {
        "notification.redis.idempotency.enabled=true",
        "notification.redis.rate-limit.enabled=true",
        "notification.redis.dead-letter.enabled=true",
        "notification.redis.key-prefix=test-wiring",
        // Closed port — all three beans should register without
        // attempting to connect. Lazy connection keeps context refresh
        // clean even with no Redis on the machine.
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        "logging.level.io.lettuce=ERROR",
        "logging.level.org.springframework=WARN",
})
class RedisBeansWiringTest {

    @Autowired RedisIdempotencyStore redisIdempotencyStore;
    @Autowired RedisRateLimiter redisRateLimiter;
    @Autowired RedisDeadLetterStore redisDeadLetterStore;

    @Test
    void allThreeRedisBeansAreRegistered() {
        assertThat(redisIdempotencyStore).isNotNull();
        assertThat(redisRateLimiter).isNotNull();
        assertThat(redisDeadLetterStore).isNotNull();
    }

    @Test
    void redisBeansImplementTheirSpis() {
        // Each Redis bean implements the SPI it backs. With
        // @ConditionalOnMissingBean wiring, a request for the SPI by
        // interface should resolve to the Redis impl.
        assertThat((IdempotencyStore) redisIdempotencyStore).isNotNull();
        assertThat((RateLimiter) redisRateLimiter).isNotNull();
        assertThat((DeadLetterStore) redisDeadLetterStore).isNotNull();
    }
}
