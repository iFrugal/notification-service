package com.lazydevs.notification.core.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitOverride;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitProperties;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitRule;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Redis-backed bean (DD-13, future) replace it via
 * {@link ConditionalOnMissingBean}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "notification.rate-limit", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean(RateLimiter.class)
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

    private final RateLimitProperties config;
    private final Cache<RateLimitKey, Bucket> buckets;

    /**
     * Sorted-by-specificity-desc snapshot of overrides — built once at
     * startup so {@link #ruleFor} doesn't have to re-sort per request.
     */
    private final List<RateLimitOverride> sortedOverrides;

    public Bucket4jRateLimiter(NotificationProperties properties) {
        this.config = properties.getRateLimit();
        this.buckets = Caffeine.newBuilder()
                .maximumSize(BUCKET_CACHE_MAX_SIZE)
                .expireAfterAccess(BUCKET_CACHE_IDLE_TTL)
                .build();
        // Most specific match first: (tenant, caller, channel) >
        // (tenant, caller) > (tenant). Ties broken by configuration order.
        this.sortedOverrides = config.getOverrides() == null
                ? List.of()
                : config.getOverrides().stream()
                        .sorted(Comparator.comparingInt(this::specificity).reversed())
                        .toList();
        log.info("Bucket4jRateLimiter initialized: default={}, {} override(s), maxBuckets={}",
                config.getDefaultRule(), sortedOverrides.size(), BUCKET_CACHE_MAX_SIZE);
    }

    @Override
    public Decision tryConsume(RateLimitKey key) {
        Bucket bucket = buckets.get(key, this::buildBucket);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return Decision.allow();
        }
        // nanosToWaitForRefill is documented as "time until at least 1 token is available"
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
     * Build a Bucket4j bucket from the resolved rule for this key.
     * Called by Caffeine on cache miss, so it runs at most once per
     * unique key (until eviction).
     */
    private Bucket buildBucket(RateLimitKey key) {
        RateLimitRule rule = ruleFor(key);
        // New builder API — replaces the deprecated Bandwidth.classic /
        // Refill.greedy combo (CodeQL flagged both as deprecated).
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(rule.getCapacity())
                .refillGreedy(rule.getRefillTokens(), rule.getRefillPeriod())
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }

    /**
     * Resolve which rule applies to this key, walking from most specific
     * to least specific:
     *
     * <pre>
     *   (tenant, caller, channel) → (tenant, caller, *) → (tenant, *, *) → default
     * </pre>
     */
    private RateLimitRule ruleFor(RateLimitKey key) {
        for (RateLimitOverride o : sortedOverrides) {
            if (matches(o, key)) {
                return o;
            }
        }
        return config.getDefaultRule();
    }

    private static boolean matches(RateLimitOverride o, RateLimitKey key) {
        if (!key.tenantId().equals(o.getTenant())) {
            return false;
        }
        if (o.getCaller() != null && !o.getCaller().equals(key.callerId())) {
            return false;
        }
        if (o.getChannel() != null && !o.getChannel().equalsIgnoreCase(key.channel())) {
            return false;
        }
        return true;
    }

    private int specificity(RateLimitOverride o) {
        // Tenant is required; +1 each for caller and channel narrowing.
        int s = 1;
        if (o.getCaller() != null) s++;
        if (o.getChannel() != null) s++;
        return s;
    }

    /**
     * Read-only view of currently-tracked buckets and their available
     * tokens. Used by the admin endpoint — not part of the
     * {@link RateLimiter} SPI because the SPI is meant to stay portable
     * across backends and Redis won't expose this kind of in-memory
     * introspection.
     */
    public Map<RateLimitKey, Long> snapshot() {
        Map<RateLimitKey, Long> out = new LinkedHashMap<>();
        // asMap() gives a live view — iterating is safe, mutating it is not.
        buckets.asMap().forEach((k, v) -> out.put(k, v.getAvailableTokens()));
        return Map.copyOf(out);
    }

}
