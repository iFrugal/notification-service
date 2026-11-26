package com.lazydevs.notification.core.ratelimit;

import com.lazydevs.notification.api.ratelimit.RateLimiter.RateLimitKey;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitBandwidth;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitOverride;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitProperties;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitRule;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitScope;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConfigurationBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Resolves which {@link RateLimitRule} governs a {@link RateLimitKey}, and
 * turns a rule into a Bucket4j {@link BucketConfiguration}. Shared by the
 * in-memory {@link Bucket4jRateLimiter} and the Redis-backed limiter so both
 * apply the same precedence (DD-12, DD-23) and the same bands and scope
 * (DD-30).
 *
 * <p>Precedence, most specific first:
 * <pre>
 *   override (tenant, caller, channel) → override (tenant, caller) → override (tenant)
 *     → by-channel[channel] → default-rule
 * </pre>
 * Within one specificity, configuration order wins. The winning rule's
 * {@link RateLimitScope} then decides which bucket the request draws from,
 * so an override still matches by tenant and caller even when its bucket is
 * shared per channel.
 *
 * @since 1.2.1
 */
public final class RateLimitRuleResolver {

    /**
     * The rule that governs a key.
     *
     * @param rule   the winning rule
     * @param ruleId stable identifier of the rule: {@code default},
     *               {@code by-channel:<channel>} or {@code override:<index>}
     *               where the index is the override's position in
     *               configuration order
     */
    public record Resolution(RateLimitRule rule, String ruleId) {

        public Resolution {
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(ruleId, "ruleId");
        }

        /**
         * Whether every request resolving to this rule on one channel shares
         * a single bucket ({@link RateLimitScope#CHANNEL}).
         */
        public boolean channelScoped() {
            return rule.getScope() == RateLimitScope.CHANNEL;
        }
    }

    private record IndexedOverride(RateLimitOverride override, int index) {
    }

    private final RateLimitProperties config;
    private final List<IndexedOverride> sortedOverrides;

    public RateLimitRuleResolver(RateLimitProperties config) {
        this.config = config;
        List<RateLimitOverride> overrides = config.getOverrides() == null ? List.of() : config.getOverrides();
        List<IndexedOverride> indexed = new ArrayList<>(overrides.size());
        for (int i = 0; i < overrides.size(); i++) {
            indexed.add(new IndexedOverride(overrides.get(i), i));
        }
        // Stable sort: ties keep configuration order.
        this.sortedOverrides = indexed.stream()
                .sorted(Comparator.comparingInt((IndexedOverride o) -> specificity(o.override())).reversed())
                .toList();
    }

    /** Number of configured overrides, for start-up logging. */
    public int overrideCount() {
        return sortedOverrides.size();
    }

    /**
     * The rule that governs {@code key}.
     */
    public Resolution resolve(RateLimitKey key) {
        for (IndexedOverride o : sortedOverrides) {
            if (matches(o.override(), key)) {
                return new Resolution(o.override(), "override:" + o.index());
            }
        }
        String channel = key.channel().toLowerCase(Locale.ROOT);
        RateLimitRule perChannel = config.getByChannel().get(channel);
        if (perChannel != null) {
            return new Resolution(perChannel, "by-channel:" + channel);
        }
        return new Resolution(config.getDefaultRule(), "default");
    }

    /**
     * The Bucket4j configuration for a rule: one greedy-refill band per
     * {@link RateLimitRule#effectiveBandwidths() effective bandwidth}.
     */
    public static BucketConfiguration bucketConfiguration(RateLimitRule rule) {
        ConfigurationBuilder builder = BucketConfiguration.builder();
        for (RateLimitBandwidth band : rule.effectiveBandwidths()) {
            // Builder API, not the deprecated Bandwidth.classic / Refill.greedy.
            builder.addLimit(Bandwidth.builder()
                    .capacity(band.getCapacity())
                    .refillGreedy(band.getRefillTokens(), band.getRefillPeriod())
                    .build());
        }
        return builder.build();
    }

    private static boolean matches(RateLimitOverride o, RateLimitKey key) {
        if (!key.tenantId().equals(o.getTenant())) {
            return false;
        }
        if (o.getCaller() != null && !o.getCaller().equals(key.callerId())) {
            return false;
        }
        return o.getChannel() == null || o.getChannel().equalsIgnoreCase(key.channel());
    }

    private static int specificity(RateLimitOverride o) {
        // Tenant is required; +1 each for caller and channel narrowing.
        int s = 1;
        if (o.getCaller() != null) {
            s++;
        }
        if (o.getChannel() != null) {
            s++;
        }
        return s;
    }
}
