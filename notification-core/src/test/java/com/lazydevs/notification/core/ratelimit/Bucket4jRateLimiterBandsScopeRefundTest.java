package com.lazydevs.notification.core.ratelimit;

import com.lazydevs.notification.api.ratelimit.RateLimiter.Decision;
import com.lazydevs.notification.api.ratelimit.RateLimiter.RateLimitKey;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitBandwidth;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitOverride;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitRule;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitScope;
import io.github.bucket4j.TimeMeter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-30 behaviour of the in-memory {@link Bucket4jRateLimiter}: several
 * bandwidths per rule, the {@code CHANNEL} bucket scope, and token refunds.
 * Time is driven by a manual clock so multi-band waits are exact.
 */
class Bucket4jRateLimiterBandsScopeRefundTest {

    /** Clock the test moves by hand. */
    static final class ManualTimeMeter implements TimeMeter {
        private long nanos;

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }

        @Override
        public long currentTimeNanos() {
            return nanos;
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    }

    private NotificationProperties properties;
    private final ManualTimeMeter clock = new ManualTimeMeter();

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setDefaultRule(new RateLimitRule(100, 100, Duration.ofSeconds(1)));
    }

    private Bucket4jRateLimiter limiter() {
        return new Bucket4jRateLimiter(properties, clock);
    }

    // ----------------------------------------------------------- bandwidths

    @Test
    void twoBands_twoPerMinuteAndFivePerHour_bothMustAllow() {
        properties.getRateLimit().setDefaultRule(bands(
                new RateLimitBandwidth(2, 2, Duration.ofMinutes(1)),
                new RateLimitBandwidth(5, 5, Duration.ofHours(1))));
        Bucket4jRateLimiter limiter = limiter();
        RateLimitKey key = new RateLimitKey("acme", "billing", "sms");

        assertThat(limiter.tryConsume(key).allowed()).isTrue();
        assertThat(limiter.tryConsume(key).allowed()).isTrue();
        Decision minuteDenied = limiter.tryConsume(key);
        assertThat(minuteDenied.allowed()).isFalse();
        // The minute band refills one token every 30 s; the hour band still has 3.
        assertThat(minuteDenied.retryAfter()).isBetween(Duration.ofSeconds(29), Duration.ofSeconds(30));

        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.tryConsume(key).allowed()).isTrue();
        assertThat(limiter.tryConsume(key).allowed()).isTrue();

        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.tryConsume(key).allowed()).as("fifth send in the hour").isTrue();
        Decision hourDenied = limiter.tryConsume(key);
        assertThat(hourDenied.allowed()).as("the minute band has a token, the hour band does not").isFalse();
        // The reported wait is until every band has a token: the hour band's ~10 minutes.
        assertThat(hourDenied.retryAfter()).isBetween(Duration.ofMinutes(9), Duration.ofMinutes(10));
    }

    @Test
    void singleBandFields_stillWorkWhenBandwidthsIsEmpty() {
        properties.getRateLimit().setDefaultRule(new RateLimitRule(1, 1, Duration.ofMinutes(1)));
        Bucket4jRateLimiter limiter = limiter();
        RateLimitKey key = new RateLimitKey("acme", "billing", "email");

        assertThat(limiter.tryConsume(key).allowed()).isTrue();
        assertThat(limiter.tryConsume(key).allowed()).isFalse();
    }

    // ---------------------------------------------------------------- scope

    @Test
    void channelScope_twoTenantsShareOneBucket() {
        RateLimitRule sms = new RateLimitRule(3, 3, Duration.ofMinutes(1));
        sms.setScope(RateLimitScope.CHANNEL);
        properties.getRateLimit().getByChannel().put("sms", sms);
        Bucket4jRateLimiter limiter = limiter();
        RateLimitKey acme = new RateLimitKey("acme", "billing", "sms");
        RateLimitKey globex = new RateLimitKey("globex", "anonymous", "SMS");

        assertThat(limiter.tryConsume(acme).allowed()).isTrue();
        assertThat(limiter.tryConsume(globex).allowed()).isTrue();
        assertThat(limiter.tryConsume(acme).allowed()).isTrue();

        assertThat(limiter.tryConsume(globex).allowed()).isFalse();
        assertThat(limiter.tryConsume(acme).allowed()).isFalse();
        assertThat(limiter.snapshot())
                .containsOnlyKeys(new RateLimitKey("*", "by-channel:sms", "sms"))
                .containsEntry(new RateLimitKey("*", "by-channel:sms", "sms"), 0L);
    }

    @Test
    void channelScopedDefaultRule_keepsOneBucketPerChannel() {
        RateLimitRule rule = new RateLimitRule(1, 1, Duration.ofMinutes(1));
        rule.setScope(RateLimitScope.CHANNEL);
        properties.getRateLimit().setDefaultRule(rule);
        Bucket4jRateLimiter limiter = limiter();

        assertThat(limiter.tryConsume(new RateLimitKey("acme", "a", "email")).allowed()).isTrue();
        assertThat(limiter.tryConsume(new RateLimitKey("acme", "a", "sms")).allowed()).isTrue();
        assertThat(limiter.tryConsume(new RateLimitKey("globex", "b", "email")).allowed()).isFalse();
    }

    @Test
    void tenantOverride_stillMatchesWhenTheChannelRuleIsShared() {
        RateLimitRule sms = new RateLimitRule(1, 1, Duration.ofMinutes(1));
        sms.setScope(RateLimitScope.CHANNEL);
        properties.getRateLimit().getByChannel().put("sms", sms);
        properties.getRateLimit().setOverrides(List.of(override("acme", 2, RateLimitScope.TENANT_CALLER)));
        Bucket4jRateLimiter limiter = limiter();
        RateLimitKey acme = new RateLimitKey("acme", "billing", "sms");

        // acme's override wins over the shared channel rule and has its own bucket.
        assertThat(limiter.tryConsume(acme).allowed()).isTrue();
        assertThat(limiter.tryConsume(acme).allowed()).isTrue();
        assertThat(limiter.tryConsume(acme).allowed()).isFalse();
        // Everyone else shares the one-token channel bucket.
        assertThat(limiter.tryConsume(new RateLimitKey("globex", "x", "sms")).allowed()).isTrue();
        assertThat(limiter.tryConsume(new RateLimitKey("initech", "y", "sms")).allowed()).isFalse();
    }

    @Test
    void channelScopedOverride_isSharedByTheTenantsCallersOnly() {
        properties.getRateLimit().setOverrides(List.of(override("acme", 2, RateLimitScope.CHANNEL)));
        properties.getRateLimit().setDefaultRule(new RateLimitRule(1, 1, Duration.ofMinutes(1)));
        Bucket4jRateLimiter limiter = limiter();

        assertThat(limiter.tryConsume(new RateLimitKey("acme", "billing", "email")).allowed()).isTrue();
        assertThat(limiter.tryConsume(new RateLimitKey("acme", "marketing", "email")).allowed()).isTrue();
        assertThat(limiter.tryConsume(new RateLimitKey("acme", "ops", "email")).allowed()).isFalse();
        // Another tenant resolves to the default rule and its own bucket.
        assertThat(limiter.tryConsume(new RateLimitKey("globex", "billing", "email")).allowed()).isTrue();
        assertThat(limiter.snapshot()).containsKey(new RateLimitKey("*", "override:0", "email"));
    }

    @Test
    void realTenantNamedLikeTheSharedKey_doesNotDrainTheSharedBucket() {
        RateLimitRule sms = new RateLimitRule(1, 1, Duration.ofMinutes(1));
        sms.setScope(RateLimitScope.CHANNEL);
        properties.getRateLimit().getByChannel().put("sms", sms);
        Bucket4jRateLimiter limiter = limiter();

        // Uses the shared bucket like everyone else (same rule)...
        assertThat(limiter.tryConsume(new RateLimitKey("*", "by-channel:sms", "sms")).allowed()).isTrue();
        assertThat(limiter.tryConsume(new RateLimitKey("acme", "billing", "sms")).allowed()).isFalse();
        // ...but a tenant-scoped rule never lands in a shared bucket.
        assertThat(limiter.tryConsume(new RateLimitKey("*", "by-channel:sms", "email")).allowed()).isTrue();
    }

    // --------------------------------------------------------------- refund

    @Test
    void refund_givesOneTokenBack() {
        properties.getRateLimit().setDefaultRule(new RateLimitRule(1, 1, Duration.ofHours(1)));
        Bucket4jRateLimiter limiter = limiter();
        RateLimitKey key = new RateLimitKey("acme", "billing", "email");
        assertThat(limiter.tryConsume(key).allowed()).isTrue();
        assertThat(limiter.tryConsume(key).allowed()).isFalse();

        limiter.refund(key);

        assertThat(limiter.tryConsume(key).allowed()).isTrue();
        assertThat(limiter.tryConsume(key).allowed()).isFalse();
    }

    @Test
    void refund_neverFillsAboveCapacity() {
        properties.getRateLimit().setDefaultRule(new RateLimitRule(3, 3, Duration.ofHours(1)));
        Bucket4jRateLimiter limiter = limiter();
        RateLimitKey key = new RateLimitKey("acme", "billing", "email");
        limiter.tryConsume(key);

        limiter.refund(key);
        limiter.refund(key);
        limiter.refund(key);

        assertThat(limiter.snapshot()).containsEntry(key, 3L);
    }

    @Test
    void refund_forAKeyWithoutABucket_isANoOp() {
        Bucket4jRateLimiter limiter = limiter();

        limiter.refund(new RateLimitKey("acme", "billing", "email"));

        assertThat(limiter.snapshot()).isEmpty();
    }

    @Test
    void refund_goesToTheSharedChannelBucket() {
        RateLimitRule sms = new RateLimitRule(1, 1, Duration.ofHours(1));
        sms.setScope(RateLimitScope.CHANNEL);
        properties.getRateLimit().getByChannel().put("sms", sms);
        Bucket4jRateLimiter limiter = limiter();
        RateLimitKey acme = new RateLimitKey("acme", "billing", "sms");
        RateLimitKey globex = new RateLimitKey("globex", "anonymous", "sms");
        assertThat(limiter.tryConsume(acme).allowed()).isTrue();
        assertThat(limiter.tryConsume(globex).allowed()).isFalse();

        limiter.refund(acme);

        assertThat(limiter.tryConsume(globex).allowed()).isTrue();
    }

    @Test
    void refund_withTwoBands_restoresBothWithinTheirCapacity() {
        properties.getRateLimit().setDefaultRule(bands(
                new RateLimitBandwidth(1, 1, Duration.ofMinutes(1)),
                new RateLimitBandwidth(2, 2, Duration.ofHours(1))));
        Bucket4jRateLimiter limiter = limiter();
        RateLimitKey key = new RateLimitKey("acme", "billing", "sms");
        assertThat(limiter.tryConsume(key).allowed()).isTrue();

        limiter.refund(key);

        assertThat(limiter.tryConsume(key).allowed()).isTrue();
        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.tryConsume(key).allowed()).as("hour band still had one token").isTrue();
        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.tryConsume(key).allowed()).as("hour band exhausted").isFalse();
    }

    @Test
    void publicConstructor_usesTheSystemClock() {
        Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(properties);

        assertThat(limiter.tryConsume(new RateLimitKey("acme", "billing", "email")).allowed()).isTrue();
    }

    // -------------------------------------------------------------- helpers

    private static RateLimitRule bands(RateLimitBandwidth... bandwidths) {
        RateLimitRule rule = new RateLimitRule();
        rule.setBandwidths(List.of(bandwidths));
        return rule;
    }

    private static RateLimitOverride override(String tenant, long capacity, RateLimitScope scope) {
        RateLimitOverride o = new RateLimitOverride();
        o.setTenant(tenant);
        o.setCapacity(capacity);
        o.setRefillTokens(capacity);
        o.setRefillPeriod(Duration.ofHours(1));
        o.setScope(scope);
        return o;
    }
}
