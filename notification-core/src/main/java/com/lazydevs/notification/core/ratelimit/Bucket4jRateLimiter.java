package com.lazydevs.notification.core.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.local.LocalBucketBuilder;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Default in-memory {@link RateLimiter} backed by Bucket4j (DD-12).
 *
 * <p>Each unique resolved {@code (tenant, caller, channel)} gets its own
 * {@link Bucket}. The bucket store is a <strong>bounded</strong>
 * Caffeine cache — earlier drafts used a plain {@code ConcurrentHashMap}
 * but that exposes a memory-pressure surface: a caller varying
 * {@code X-Service-Id} per request would create one entry per value
 * indefinitely. Caffeine bounds the count and evicts least-recently-used
 * keys; an evicted bucket re-creates fresh on next hit (state is a
 * full bucket — not a security regression because by definition
 * "least recently used" means it hasn't been hammered lately, and the
 * refill period would have brought it close to full anyway).
 *
 * <p>Override-precedence is resolved at decision time, not at bucket
 * creation, so a bucket already created for a key reflects its own
 * pinned rule. Changing config after startup means restarting the
 * service — acceptable for the first cut.
 *
 * <p>Bean is registered only when {@code notification.rate-limit.enabled=true}
 * — keeps Bucket4j inert in deployments that don't care, and lets a
 * Redis-backed bean (DD-13, future) replace it via {@code @ConditionalOnMissingBean}
 * in {@link com.lazydevs.notification.core.config.NotificationCoreDefaultsAutoConfiguration}.
 */
@Slf4j
public class Bucket4jRateLimiter implements RateLimiter {

    /**
     * Maximum entries held in the bucket cache. Hardcoded for now —
     * exposing it as a property would invite operators to tune past the
     * point where Caffeine stays cheap. 10k entries is plenty for a
     * notification service: a single pod with 100 tenants × 5 callers
     * each × 4 channels = 2 000 active keys, leaving headroom.
     */
    private static final long BUCKET_CACHE_MAX_SIZE = 10_000L;

    /**
     * How long an idle bucket lives in the cache before being evicted.
     * 1 hour — long enough to outlast typical refill periods (so an idle
     * bucket isn't re-created on every refill cycle), short enough to
     * keep the cache bounded against an attacker varying caller-ids.
     */
    private static final Duration BUCKET_CACHE_IDLE_TTL = Duration.ofHours(1);

    /** Tenant id shown for a channel-scoped (shared) bucket in {@link #snapshot()}. */
    static final String SHARED_TENANT = "*";

    /**
     * Cache key of a bucket. A channel-scoped bucket is keyed by
     * {@code ("*", ruleId, channel)} with {@code shared=true}, so it can never
     * collide with the bucket of a real {@code (tenant, caller, channel)}.
     */
    private record BucketKey(boolean shared, RateLimitKey key) {
    }

    private final RateLimitRuleResolver resolver;
    private final Cache<BucketKey, Bucket> buckets;
    private final TimeMeter timeMeter;

    public Bucket4jRateLimiter(NotificationProperties properties) {
        this(properties, TimeMeter.SYSTEM_MILLISECONDS);
    }

    /**
     * Test seam: buckets read time from {@code timeMeter}.
     */
    Bucket4jRateLimiter(NotificationProperties properties, TimeMeter timeMeter) {
        RateLimitProperties config = properties.getRateLimit();
        this.resolver = new RateLimitRuleResolver(config);
        this.timeMeter = timeMeter;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(BUCKET_CACHE_MAX_SIZE)
                .expireAfterAccess(BUCKET_CACHE_IDLE_TTL)
                .build();
        log.info("Bucket4jRateLimiter initialized: default={}, {} override(s), maxBuckets={}",
                config.getDefaultRule(), resolver.overrideCount(), BUCKET_CACHE_MAX_SIZE);
    }

    @Override
    public Decision tryConsume(RateLimitKey key) {
        RateLimitRuleResolver.Resolution resolution = resolver.resolve(key);
        Bucket bucket = buckets.get(bucketKey(key, resolution), k -> buildBucket(resolution));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return Decision.allow();
        }
        // nanosToWaitForRefill is "time until at least 1 token is available";
        // with several bands it is the time until every band has one (DD-30).
        Duration retryAfter = Duration.ofNanos(probe.getNanosToWaitForRefill());
        // Don't log the user-controlled key components here — taint-tracking
        // tools can't statically prove our control-char scrub is sufficient,
        // and the (sanitized) RateLimitExceededException message that
        // GlobalExceptionHandler logs already carries the same context.
        // Only the duration (a long, not user-controlled) is logged.
        log.debug("Rate limit hit — retry in {}ms", retryAfter.toMillis());
        return Decision.deny(retryAfter);
    }

    /**
     * Give one token back to the bucket {@code key} resolves to (DD-30).
     * Bucket4j's {@code addTokens} never fills a band above its capacity. A
     * bucket that is no longer cached is full by definition, so there is
     * nothing to give back.
     */
    @Override
    public void refund(RateLimitKey key) {
        Bucket bucket = buckets.getIfPresent(bucketKey(key, resolver.resolve(key)));
        if (bucket != null) {
            bucket.addTokens(1);
        }
    }

    private static BucketKey bucketKey(RateLimitKey key, RateLimitRuleResolver.Resolution resolution) {
        if (resolution.channelScoped()) {
            return new BucketKey(true, new RateLimitKey(SHARED_TENANT, resolution.ruleId(),
                    key.channel().toLowerCase(Locale.ROOT)));
        }
        return new BucketKey(false, key);
    }

    /**
     * Build a Bucket4j bucket from the resolved rule. Called by Caffeine on
     * cache miss, so it runs at most once per bucket key (until eviction).
     */
    private Bucket buildBucket(RateLimitRuleResolver.Resolution resolution) {
        LocalBucketBuilder builder = Bucket.builder().withCustomTimePrecision(timeMeter);
        for (Bandwidth band : RateLimitRuleResolver.bucketConfiguration(resolution.rule()).getBandwidths()) {
            builder.addLimit(band);
        }
        return builder.build();
    }

    /**
     * Read-only view of currently-tracked buckets and their available
     * tokens. Used by the admin endpoint — not part of the
     * {@link RateLimiter} SPI because the SPI is meant to stay portable
     * across backends and Redis won't expose this kind of in-memory
     * introspection.
     *
     * <p>A channel-scoped bucket (DD-30) is listed under
     * {@code ("*", ruleId, channel)}. With several bands, the count is the
     * smallest band's.
     */
    public Map<RateLimitKey, Long> snapshot() {
        Map<RateLimitKey, Long> out = new LinkedHashMap<>();
        // asMap() gives a live view — iterating is safe, mutating it is not.
        buckets.asMap().forEach((k, v) -> out.put(k.key(), v.getAvailableTokens()));
        return Map.copyOf(out);
    }

}
