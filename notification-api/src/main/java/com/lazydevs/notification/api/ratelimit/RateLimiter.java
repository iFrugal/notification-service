package com.lazydevs.notification.api.ratelimit;

import java.time.Duration;
import java.util.Objects;

/**
 * SPI for the per-{@code (tenant, caller, channel)} rate-limit check
 * introduced in DD-12.
 *
 * <p>The default in-memory implementation lives in {@code notification-core}
 * (Bucket4j-backed). A future Redis-backed implementation will plug in by
 * replacing the bean — same shape as the {@code IdempotencyStore} SPI from
 * DD-10.
 *
 * <p>The contract is intentionally narrow: no "release", no introspection,
 * no quotas. {@link #tryConsume(RateLimitKey)} is one call per request
 * inside {@code DefaultNotificationService.send()}; everything else is the
 * implementation's business.
 */
public interface RateLimiter {

    /**
     * Try to consume a single token for this scope.
     *
     * @param key the resolved {@code (tenant, caller, channel)} triple
     *            — none of the components may be {@code null}
     * @return a {@link Decision} indicating whether the request is allowed
     *         and, if not, how long until the next token becomes available
     */
    Decision tryConsume(RateLimitKey key);

    /**
     * Composite key under which the limiter tracks a single token bucket.
     * The triple is the most-specific scope DD-12 supports — less-specific
     * configurations (e.g. tenant-only overrides) are resolved by the
     * implementation, not by collapsing components to {@code null} here.
     *
     * @param tenantId tenant identifier (DD-03). Never {@code null}.
     * @param callerId caller identifier (DD-11). May be the literal
     *                 string {@code "anonymous"} when the caller did not
     *                 send {@code X-Service-Id} — the implementation
     *                 chooses whether to bucket anonymous traffic
     *                 separately.
     * @param channel  channel name (e.g. {@code "email"}). Never
     *                 {@code null}.
     */
    record RateLimitKey(String tenantId, String callerId, String channel) {
        public RateLimitKey {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(callerId, "callerId");
            Objects.requireNonNull(channel, "channel");
        }
    }

    /**
     * Result of a {@link #tryConsume} call.
     *
     * @param allowed     {@code true} if the request may proceed
     * @param retryAfter  when {@code allowed} is {@code false}, the
     *                    estimated time until the next token becomes
     *                    available. {@link Duration#ZERO} when allowed.
     */
    record Decision(boolean allowed, Duration retryAfter) {

        public Decision {
            Objects.requireNonNull(retryAfter, "retryAfter");
        }

        /** Convenience: allowed with zero retry-after. */
        public static Decision allow() {
            return new Decision(true, Duration.ZERO);
        }

        /** Convenience: denied with the given retry-after. */
        public static Decision deny(Duration retryAfter) {
            return new Decision(false, retryAfter);
        }
    }
}
