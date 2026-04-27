package com.lazydevs.notification.api.retry;

import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.SendResult;

/**
 * Decides whether a failed {@link SendResult} should be retried (DD-13).
 *
 * <p>Operators can plug a custom implementation as a Spring bean — the
 * service uses {@code @ConditionalOnMissingBean} for the default. The
 * default policy retries {@link FailureType#TRANSIENT} and
 * {@link FailureType#UNKNOWN}, skips {@link FailureType#PERMANENT}.
 *
 * <p>Note: the {@code attempt} parameter is the 1-based index of the
 * attempt that just failed. The retry executor checks
 * {@code shouldRetry} before sleeping for the next backoff window, so
 * returning {@code true} with {@code attempt == maxAttempts} is a
 * no-op — the executor stops at the configured cap regardless.
 */
@FunctionalInterface
public interface RetryPredicate {

    /**
     * @param result  the failed send result the provider just returned
     * @param attempt 1-based count of the attempt that produced
     *                {@code result}
     * @return {@code true} if the executor should retry; {@code false}
     *         to stop and surface the current failure to the caller
     */
    boolean shouldRetry(SendResult result, int attempt);

    /**
     * The default policy: retry on TRANSIENT and UNKNOWN, never on
     * PERMANENT. Exposed as a static so the service can fall back to it
     * when no custom bean is provided, and tests can reference it
     * directly.
     */
    RetryPredicate DEFAULT = (result, attempt) -> {
        if (result == null || result.success()) {
            return false;
        }
        FailureType ft = result.failureType();
        if (ft == null) {
            // Defensive: a SendResult constructed before DD-13 with the
            // old API might leave failureType null. Treat as UNKNOWN —
            // matches the new factories' default.
            return true;
        }
        return ft != FailureType.PERMANENT;
    };
}
