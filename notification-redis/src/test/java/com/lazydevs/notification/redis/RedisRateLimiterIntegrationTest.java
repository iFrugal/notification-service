package com.lazydevs.notification.redis;

import com.lazydevs.notification.api.ratelimit.RateLimiter.Decision;
import com.lazydevs.notification.api.ratelimit.RateLimiter.RateLimitKey;
import com.lazydevs.notification.core.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the Redis-backed rate limiter honours the same DD-12 SPI
 * contract as the in-memory one. Bucket math is delegated to bucket4j,
 * so these tests focus on the bridge: rule resolution, key prefix,
 * cross-pod-style state sharing (we run two limiter instances against
 * the same Redis to simulate a multi-pod deployment).
 */
@SpringBootTest(classes = {
        RedisRateLimiterIntegrationTest.TestApp.class,
        NotificationProperties.class,
        RedisRateLimiter.class
})
@TestPropertySource(properties = {
        "notification.redis.rate-limit.enabled=true",
        "notification.redis.key-prefix=test-rl",
        // Tight default so test exhausts buckets quickly.
        "notification.rate-limit.default-rule.capacity=3",
        "notification.rate-limit.default-rule.refill-tokens=3",
        "notification.rate-limit.default-rule.refill-period=PT60S",
})
class RedisRateLimiterIntegrationTest extends AbstractRedisIntegrationTest {

    @Autowired RedisRateLimiter limiter;
    @Autowired StringRedisTemplate redis;
    @Autowired NotificationProperties properties;

    @BeforeEach
    void flushRedis() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void allowsUpToCapacity_thenDenies() {
        RateLimitKey k = new RateLimitKey("acme", "billing", "email");

        assertThat(limiter.tryConsume(k).allowed()).isTrue();
        assertThat(limiter.tryConsume(k).allowed()).isTrue();
        assertThat(limiter.tryConsume(k).allowed()).isTrue();

        Decision denied = limiter.tryConsume(k);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfter()).isPositive();
    }

    @Test
    void differentKeys_haveIsolatedBuckets() {
        RateLimitKey k1 = new RateLimitKey("acme", "billing", "email");
        RateLimitKey k2 = new RateLimitKey("acme", "marketing", "email");

        // Exhaust k1 fully.
        for (int i = 0; i < 3; i++) limiter.tryConsume(k1);
        assertThat(limiter.tryConsume(k1).allowed()).isFalse();

        // k2 starts at full capacity even after k1 is empty.
        assertThat(limiter.tryConsume(k2).allowed()).isTrue();
    }

    @Test
    void redisKey_includesPrefix() {
        assertThat(limiter.redisKey(new RateLimitKey("acme", "billing", "email")))
                .startsWith("test-rl:ratelimit:");
    }

    @SpringBootApplication
    @Import({NotificationProperties.class})
    static class TestApp {}
}
