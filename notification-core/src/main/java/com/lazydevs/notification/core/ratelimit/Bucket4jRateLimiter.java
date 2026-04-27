package com.lazydevs.notification.core.ratelimit;

import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitOverride;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitProperties;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitRule;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-memory {@link RateLimiter} backed by Bucket4j (DD-12).
 *
 * <p>Each unique resolved {@code (tenant, caller, channel)} gets its own
 * {@link Bucket}, lazily instantiated on first call and held in a
 * {@link ConcurrentHashMap}. The map is bounded by configuration size
 * (operators control the unique-tuple count via overrides), not by request
 * volume — DD-12 §Negative consequences.
 *
 * <p>Override-precedence is resolved at decision time, not at bucket
 * creation, so a bucket already created for a key reflects its own pinned
 * rule. Changing config after startup means restarting the service —
 * acceptable for the first cut. (Future enhancement: an admin endpoint to
 * reload rules.)
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

    private final RateLimitProperties config;
    private final ConcurrentHashMap<RateLimitKey, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Sorted-by-specificity-desc snapshot of overrides — built once at
     * startup so {@link #ruleFor} doesn't have to re-sort per request.
     */
    private final List<RateLimitOverride> sortedOverrides;

    public Bucket4jRateLimiter(NotificationProperties properties) {
        this.config = properties.getRateLimit();
        // Most specific match first: (tenant, caller, channel) >
        // (tenant, caller) > (tenant). Ties broken by configuration order.
        this.sortedOverrides = config.getOverrides() == null
                ? List.of()
                : config.getOverrides().stream()
                        .sorted(Comparator.comparingInt(this::specificity).reversed())
                        .toList();
        log.info("Bucket4jRateLimiter initialized: default={}, {} override(s)",
                config.getDefaultRule(), sortedOverrides.size());
    }

    @Override
    public Decision tryConsume(RateLimitKey key) {
        Bucket bucket = buckets.computeIfAbsent(key, this::buildBucket);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return Decision.allow();
        }
        // nanosToWaitForRefill is documented as "time until at least 1 token is available"
        Duration retryAfter = Duration.ofNanos(probe.getNanosToWaitForRefill());
        log.debug("Rate limit hit for tenant={}, caller={}, channel={} — retry in {}ms",
                key.tenantId(), key.callerId(), key.channel(), retryAfter.toMillis());
        return Decision.deny(retryAfter);
    }

    /**
     * Build a Bucket4j bucket from the resolved rule for this key. Called
     * by {@code computeIfAbsent}, so it runs at most once per unique key.
     */
    private Bucket buildBucket(RateLimitKey key) {
        RateLimitRule rule = ruleFor(key);
        Bandwidth bandwidth = Bandwidth.classic(
                rule.getCapacity(),
                Refill.greedy(rule.getRefillTokens(), rule.getRefillPeriod()));
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
     * tokens. Used by the admin endpoint — not part of the {@link RateLimiter}
     * SPI because the SPI is meant to stay portable across backends and
     * Redis won't expose this kind of in-memory introspection.
     */
    public Map<RateLimitKey, Long> snapshot() {
        return Map.copyOf(
                buckets.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                e -> e.getValue().getAvailableTokens(),
                                (a, b) -> a,
                                java.util.LinkedHashMap::new)));
    }
}
