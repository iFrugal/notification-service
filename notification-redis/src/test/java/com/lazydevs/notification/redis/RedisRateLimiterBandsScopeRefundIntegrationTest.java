package com.lazydevs.notification.redis;

import com.lazydevs.notification.api.ratelimit.RateLimiter.Decision;
import com.lazydevs.notification.api.ratelimit.RateLimiter.RateLimitKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-30 against a real Redis: several bandwidths per rule, the
 * {@code CHANNEL} bucket scope shared across tenants, and token refunds,
 * all through the same {@code RateLimitRuleResolver} the in-memory limiter
 * uses.
 */
@SpringBootTest(classes = TestRedisApp.class)
@TestPropertySource(properties = {
        "notification.rate-limit.enabled=true",
        "notification.redis.rate-limit.enabled=true",
        "notification.redis.key-prefix=test-rl30",
        // Default: two bands, 2 per minute and 3 per hour.
        "notification.rate-limit.default-rule.bandwidths[0].capacity=2",
        "notification.rate-limit.default-rule.bandwidths[0].refill-tokens=2",
        "notification.rate-limit.default-rule.bandwidths[0].refill-period=PT1M",
        "notification.rate-limit.default-rule.bandwidths[1].capacity=3",
        "notification.rate-limit.default-rule.bandwidths[1].refill-tokens=3",
        "notification.rate-limit.default-rule.bandwidths[1].refill-period=PT1H",
        // SMS: one provider-account bucket shared by every tenant.
        "notification.rate-limit.by-channel.sms.capacity=2",
        "notification.rate-limit.by-channel.sms.refill-tokens=2",
        "notification.rate-limit.by-channel.sms.refill-period=PT1H",
        "notification.rate-limit.by-channel.sms.scope=channel",
})
class RedisRateLimiterBandsScopeRefundIntegrationTest extends AbstractRedisIntegrationTest {

    @Autowired RedisRateLimiter limiter;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void flushRedis() {
        redis.execute((RedisCallback<Void>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void twoBands_theTighterBandDeniesWithItsWait() {
        RateLimitKey key = new RateLimitKey("acme", "billing", "email");

        assertThat(limiter.tryConsume(key).allowed()).isTrue();
        assertThat(limiter.tryConsume(key).allowed()).isTrue();
        Decision denied = limiter.tryConsume(key);

        assertThat(denied.allowed()).isFalse();
        // The minute band refills one token every 30 s; the hour band still has one.
        assertThat(denied.retryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void channelScope_twoTenantsShareOneRedisBucket() {
        RateLimitKey acme = new RateLimitKey("acme", "billing", "sms");
        RateLimitKey globex = new RateLimitKey("globex", "anonymous", "sms");

        assertThat(limiter.tryConsume(acme).allowed()).isTrue();
        assertThat(limiter.tryConsume(globex).allowed()).isTrue();
        assertThat(limiter.tryConsume(acme).allowed()).isFalse();
        assertThat(limiter.tryConsume(globex).allowed()).isFalse();

        assertThat(limiter.redisKey(acme)).isEqualTo("test-rl30:ratelimit-shared:by-channel:sms:sms")
                .isEqualTo(limiter.redisKey(globex));
        assertThat(redis.hasKey("test-rl30:ratelimit-shared:by-channel:sms:sms")).isTrue();
    }

    @Test
    void tenantCallerScope_keepsTheExistingKeyFormat() {
        assertThat(limiter.redisKey(new RateLimitKey("acme", "billing", "email")))
                .isEqualTo("test-rl30:ratelimit:acme:billing:email");
    }

    @Test
    void refund_givesOneTokenBackWithoutExceedingCapacity() {
        RateLimitKey acme = new RateLimitKey("acme", "billing", "sms");
        RateLimitKey globex = new RateLimitKey("globex", "anonymous", "sms");
        assertThat(limiter.tryConsume(acme).allowed()).isTrue();
        assertThat(limiter.tryConsume(acme).allowed()).isTrue();
        assertThat(limiter.tryConsume(globex).allowed()).isFalse();

        limiter.refund(acme);

        assertThat(limiter.tryConsume(globex).allowed()).isTrue();
        assertThat(limiter.tryConsume(globex).allowed()).isFalse();
    }

    @Test
    void refund_onAFullBucket_doesNotRaiseItAboveCapacity() {
        RateLimitKey key = new RateLimitKey("acme", "billing", "email");

        limiter.refund(key);
        limiter.refund(key);

        assertThat(limiter.tryConsume(key).allowed()).isTrue();
        assertThat(limiter.tryConsume(key).allowed()).isTrue();
        assertThat(limiter.tryConsume(key).allowed()).isFalse();
    }
}
