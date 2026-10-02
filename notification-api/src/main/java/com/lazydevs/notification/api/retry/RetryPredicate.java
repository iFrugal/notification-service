package com.lazydevs.notification.api.retry;

import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.SendResult;

/**
 * Decides whether a failed {@link SendResult} should be retried (DD-13).
 *
 * <p>Operators can plug a custom implementation as a Spring bean — the
 * service uses {@code @ConditionalOnMissingBean} for the default. The
 * default policy retries {@link FailureType#TRANSIENT} and
 * {@link FailureType#UNKNOWN}, skips {@link FailureType#PERMANENT} and
 * {@link FailureType#AMBIGUOUS}.
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
     * The default policy: retry on TRANSIENT and UNKNOWN (and a
     * {@code null} failure type), never on PERMANENT or AMBIGUOUS.
     * Exposed as a static so the service can fall back to it when no
     * custom bean is provided, and tests can reference it directly.
     *
     * <p>{@link FailureType#AMBIGUOUS} is not retried because the
     * provider may already have accepted the message: a retry could
     * deliver it twice. The failure surfaces to the caller and, when
     * configured, the dead-letter store. Deployments whose providers
     * deduplicate resends can opt in with a custom predicate. A custom
     * predicate written as {@code type != PERMANENT} retries AMBIGUOUS
     * failures (and any constant added later), so prefer listing the
     * types to retry.
     *
     * <p>The {@code attempt} parameter is unused by this default
     * (renamed to {@code _} per JEP 456 — silences static analyzers
     * that flag unused lambda params). Custom predicates can still
     * read the attempt count: the interface contract preserves it.
     */
    RetryPredicate DEFAULT = (result, _) -> {
        if (result == null || result.success()) {
            return false;
        }
        FailureType ft = result.failureType();
        // A null type comes from a SendResult constructed before DD-13
        // with the old API; treat it as UNKNOWN, matching the factories.
        return ft == null || ft == FailureType.TRANSIENT || ft == FailureType.UNKNOWN;
    };
}
