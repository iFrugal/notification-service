package com.lazydevs.notification.core.retry;

import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.retry.RetryPredicate;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.RetryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * In-process retry helper for provider calls (DD-13).
 *
 * <p>Wraps a {@code Supplier<SendResult>} and re-invokes it up to
 * {@code maxAttempts} times when the {@link RetryPredicate} says the
 * failure is retry-worthy. Backoff is exponential with jitter:
 *
 * <pre>
 *   delay(n) = min(initialDelay × multiplier^(n-1), maxDelay)
 *   actual   = delay × (1 + uniform(-jitter, +jitter))
 * </pre>
 *
 * <p>The bean is registered only when
 * {@code notification.retry.enabled=true} — keeps the executor inert in
 * deployments that don't want retries. {@code @ConditionalOnMissingBean}
 * lets a future Resilience4j-backed bean replace it without changing
 * the service.
 *
 * <p>Why a custom helper rather than Resilience4j? See DD-13 §"Why a
 * custom helper rather than Resilience4j" — narrow semantics, no
 * transitive dependencies, easier to reason about than the full
 * resilience4j-retry chain.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "notification.retry", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean(RetryExecutor.class)
public class RetryExecutor {

    private final RetryProperties config;
    private final RetryPredicate predicate;

    public RetryExecutor(NotificationProperties properties,
                         java.util.Optional<RetryPredicate> customPredicate) {
        this.config = properties.getRetry();
        // Custom RetryPredicate bean wins; otherwise fall back to the
        // default policy from the SPI. Operators can plug in a
        // channel-aware or attempt-cap-aware predicate via @Bean.
        this.predicate = customPredicate.orElse(RetryPredicate.DEFAULT);
        log.info("RetryExecutor initialized: maxAttempts={}, initialDelay={}, multiplier={}, "
                        + "maxDelay={}, jitter={}",
                config.getMaxAttempts(), config.getInitialDelay(), config.getMultiplier(),
                config.getMaxDelay(), config.getJitter());
    }

    /**
     * Run {@code action}, retrying on failure per the configured
     * policy.
     *
     * @return the final {@link SendResult} — either a success, a failure
     *         the predicate said to stop on, or the last failure after
     *         exhausting attempts. Also returns the attempt count so the
     *         caller can record it on the audit / response.
     */
    public Outcome execute(Supplier<SendResult> action) {
        SendResult result = null;
        int attempt = 0;
        for (attempt = 1; attempt <= config.getMaxAttempts(); attempt++) {
            result = invokeSafely(action, attempt);
            if (result.success() || !predicate.shouldRetry(result, attempt)) {
                return new Outcome(result, attempt);
            }
            // Don't sleep after the last attempt — we're about to return
            // the failure regardless.
            if (attempt < config.getMaxAttempts()) {
                Duration backoff = computeBackoff(attempt);
                log.debug("Attempt {} failed; sleeping {}ms before retry", attempt, backoff.toMillis());
                if (!sleepUninterruptibly(backoff)) {
                    // Thread interrupted — surface the most recent failure
                    // and let the caller propagate.
                    return new Outcome(result, attempt);
                }
            }
        }
        // Exhausted attempts. attempt is now (maxAttempts + 1) due to the
        // post-increment of the for-loop; clamp for the report.
        return new Outcome(result, Math.min(attempt - 1, config.getMaxAttempts()));
    }

    /**
     * Invoke the action; if it throws, wrap the exception as a
     * {@link SendResult#failure(Exception)} so the retry loop sees a
     * uniform shape. Without this, a provider that throws (rather
     * than returning a failed result) would skip the retry path.
     */
    private SendResult invokeSafely(Supplier<SendResult> action, int attempt) {
        try {
            SendResult r = action.get();
            // Defensive: a Supplier that returns null breaks the predicate.
            // Treat as an unknown-classification failure.
            return r != null ? r
                    : SendResult.failure("NULL_RESULT",
                            "Provider returned null on attempt " + attempt);
        } catch (RuntimeException e) {
            log.debug("Attempt {} threw {}: {}", attempt, e.getClass().getSimpleName(), e.getMessage());
            return SendResult.failure(e);
        }
    }

    /**
     * Compute backoff for this attempt: exponential base, capped by
     * {@code maxDelay}, then jittered. Visible for testing.
     */
    Duration computeBackoff(int attempt) {
        // attempt is 1-based; first delay (after attempt 1 fails) uses
        // initialDelay × multiplier^0 = initialDelay.
        double base = config.getInitialDelay().toMillis()
                * Math.pow(config.getMultiplier(), attempt - 1);
        double capped = Math.min(base, config.getMaxDelay().toMillis());
        double jittered = applyJitter(capped, config.getJitter());
        return Duration.ofMillis(Math.max(0L, (long) jittered));
    }

    /**
     * Apply ±{@code jitter} fraction to {@code base}. With jitter=0.5
     * and base=1000ms the result is uniformly in [500ms, 1500ms].
     * Jitter is critical to avoid thundering-herd on shared providers
     * (DD-13 §"Why exponential backoff with jitter").
     */
    static double applyJitter(double baseMillis, double jitter) {
        if (jitter <= 0.0) {
            return baseMillis;
        }
        double clamped = Math.min(1.0, jitter);
        // ThreadLocalRandom for cheap per-thread randomness.
        double factor = 1.0 + ThreadLocalRandom.current().nextDouble(-clamped, clamped);
        return baseMillis * factor;
    }

    /**
     * Sleep for {@code d}; returns {@code true} if the sleep completed,
     * {@code false} if the thread was interrupted. We restore the
     * interrupt flag so callers higher up the stack can observe it.
     */
    private static boolean sleepUninterruptibly(Duration d) {
        if (d.isZero() || d.isNegative()) {
            return true;
        }
        try {
            Thread.sleep(d.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Result of a retry-wrapped invocation: the final {@link SendResult}
     * and the number of attempts taken (1 = no retry needed).
     */
    public record Outcome(SendResult result, int attempts) {}
}
