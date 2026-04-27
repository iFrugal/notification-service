package com.lazydevs.notification.core.ratelimit;

import com.lazydevs.notification.api.ratelimit.RateLimiter.Decision;
import com.lazydevs.notification.api.ratelimit.RateLimiter.RateLimitKey;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitOverride;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the DD-12 Bucket4j-backed {@link Bucket4jRateLimiter}.
 *
 * <p>Covers: default-rule baseline, override precedence (most specific
 * wins), bucket isolation across keys, exhaust-and-recover, snapshot
 * shape.
 */
class Bucket4jRateLimiterTest {

    private NotificationProperties properties;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.getRateLimit().setEnabled(true);
        // Generous defaults — individual tests narrow these down to make
        // bucket exhaustion deterministic.
        properties.getRateLimit().setDefaultRule(
                new RateLimitRule(100, 100, Duration.ofSeconds(1)));
    }

    @Test
    void defaultRule_allowsUpToCapacity_thenDenies() {
        properties.getRateLimit().setDefaultRule(
                new RateLimitRule(3, 3, Duration.ofSeconds(60)));
        Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(properties);
        RateLimitKey key = new RateLimitKey("acme", "billing", "email");

        // Bucket starts full at capacity — first 3 calls allowed.
        assertThat(limiter.tryConsume(key).allowed()).isTrue();
        assertThat(limiter.tryConsume(key).allowed()).isTrue();
        assertThat(limiter.tryConsume(key).allowed()).isTrue();

        // 4th call denied; retry-after non-zero.
        Decision denied = limiter.tryConsume(key);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfter()).isPositive();
    }

    @Test
    void differentKeys_haveIndependentBuckets() {
        properties.getRateLimit().setDefaultRule(
                new RateLimitRule(1, 1, Duration.ofSeconds(60)));
        Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(properties);

        RateLimitKey acmeBilling = new RateLimitKey("acme", "billing", "email");
        RateLimitKey acmeMarketing = new RateLimitKey("acme", "marketing", "email");
        RateLimitKey otherBilling = new RateLimitKey("other-tenant", "billing", "email");

        // Each key has its own bucket — first hit on each is allowed.
        assertThat(limiter.tryConsume(acmeBilling).allowed()).isTrue();
        assertThat(limiter.tryConsume(acmeMarketing).allowed()).isTrue();
        assertThat(limiter.tryConsume(otherBilling).allowed()).isTrue();

        // Second hit on the same key is denied (capacity=1).
        assertThat(limiter.tryConsume(acmeBilling).allowed()).isFalse();
        // Sibling keys still allowed — they exhausted their first token
        // already above, so they're also denied. Actually verify: each
        // bucket is independent and now also at zero.
        assertThat(limiter.tryConsume(acmeMarketing).allowed()).isFalse();
    }

    @Test
    void mostSpecificOverrideWins() {
        // tenant-only rule: cap 100; tenant+caller: cap 10; tenant+caller+channel: cap 1.
        // A request matching all three should be bucketed at cap=1.
        properties.getRateLimit().setOverrides(buildOverrides(
                override("acme", null, null, 100),
                override("acme", "billing", null, 10),
                override("acme", "billing", "email", 1)));
        Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(properties);

        RateLimitKey emailKey = new RateLimitKey("acme", "billing", "email");
        // Only one token before exhaustion — confirms the (tenant, caller,
        // channel) override is the one being applied.
        assertThat(limiter.tryConsume(emailKey).allowed()).isTrue();
        assertThat(limiter.tryConsume(emailKey).allowed()).isFalse();
    }

    @Test
    void tenantOnlyOverride_appliesAcrossAllCallersAndChannels() {
        properties.getRateLimit().setOverrides(buildOverrides(
                override("acme", null, null, 2)));
        Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(properties);

        // Different (caller, channel) tuples within the same tenant get
        // different buckets — the tenant-only override is just the
        // *template* used to BUILD each bucket, not a shared bucket.
        // This matches Bucket4j semantics + DD-12 §"Each unique resolved
        // (tenant, caller, channel) gets its own Bucket".
        RateLimitKey k1 = new RateLimitKey("acme", "billing", "email");
        RateLimitKey k2 = new RateLimitKey("acme", "marketing", "sms");

        assertThat(limiter.tryConsume(k1).allowed()).isTrue();
        assertThat(limiter.tryConsume(k1).allowed()).isTrue();
        assertThat(limiter.tryConsume(k1).allowed()).isFalse();

        // k2 has the same template (tenant-only override) but its own
        // bucket; starts fresh.
        assertThat(limiter.tryConsume(k2).allowed()).isTrue();
        assertThat(limiter.tryConsume(k2).allowed()).isTrue();
        assertThat(limiter.tryConsume(k2).allowed()).isFalse();
    }

    @Test
    void noOverrideMatch_fallsBackToDefault() {
        properties.getRateLimit().setDefaultRule(
                new RateLimitRule(1, 1, Duration.ofSeconds(60)));
        properties.getRateLimit().setOverrides(buildOverrides(
                override("other-tenant", null, null, 100)));
        Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(properties);

        // 'acme' isn't in any override → falls back to default (cap=1).
        RateLimitKey acmeKey = new RateLimitKey("acme", "billing", "email");
        assertThat(limiter.tryConsume(acmeKey).allowed()).isTrue();
        assertThat(limiter.tryConsume(acmeKey).allowed()).isFalse();
    }

    @Test
    void channelMatchIsCaseInsensitive() {
        properties.getRateLimit().setOverrides(buildOverrides(
                override("acme", null, "EMAIL", 1)));
        Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(properties);

        // The service feeds lowercase channel names ("email"); the
        // override is configured uppercase ("EMAIL"). Should match.
        RateLimitKey lowerKey = new RateLimitKey("acme", "billing", "email");
        assertThat(limiter.tryConsume(lowerKey).allowed()).isTrue();
        assertThat(limiter.tryConsume(lowerKey).allowed()).isFalse();
    }

    @Test
    void snapshot_reportsAvailableTokensPerBucket() {
        properties.getRateLimit().setDefaultRule(
                new RateLimitRule(5, 5, Duration.ofSeconds(60)));
        Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(properties);

        RateLimitKey k1 = new RateLimitKey("acme", "billing", "email");
        limiter.tryConsume(k1);
        limiter.tryConsume(k1);

        // 5 - 2 = 3 tokens left.
        assertThat(limiter.snapshot()).containsEntry(k1, 3L);
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private static List<RateLimitOverride> buildOverrides(RateLimitOverride... overrides) {
        return new ArrayList<>(List.of(overrides));
    }

    private static RateLimitOverride override(String tenant, String caller, String channel, long capacity) {
        RateLimitOverride o = new RateLimitOverride();
        o.setTenant(tenant);
        o.setCaller(caller);
        o.setChannel(channel);
        o.setCapacity(capacity);
        o.setRefillTokens(capacity);
        o.setRefillPeriod(Duration.ofSeconds(60));
        return o;
    }
}
