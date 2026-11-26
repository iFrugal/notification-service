package com.lazydevs.notification.core.idempotency;

import com.lazydevs.notification.api.model.FailureType;

/**
 * What happens to an idempotency key after a send under it failed
 * ({@code notification.idempotency.failure-policy}, DD-32).
 *
 * <p>A successful outcome ({@code SENT}, {@code DELIVERED},
 * {@code ACCEPTED}) is always kept and replayed; the policy only decides
 * about {@code FAILED} and {@code REJECTED} outcomes.
 *
 * @since 1.3.0
 */
public enum IdempotencyFailurePolicy {

    /**
     * Release the key after every failure, so a retry under the same key is
     * dispatched again. The default; the same as
     * {@code retry-after-failure=true} (1.1.2 to 1.2.0).
     */
    RELEASE_ALL,

    /**
     * Keep the failed record: a retry under the same key gets HTTP 409 until
     * the TTL elapses. The same as {@code retry-after-failure=false}
     * (1.1.0 and 1.1.1 behaviour).
     */
    KEEP,

    /**
     * Release the key only after a {@link FailureType#TRANSIENT TRANSIENT}
     * failure, which never reached the provider in a way that could deliver
     * the message. A {@link FailureType#PERMANENT PERMANENT},
     * {@link FailureType#AMBIGUOUS AMBIGUOUS} or {@link FailureType#UNKNOWN
     * UNKNOWN} failure (or one without a classification) is kept and
     * replayed to later requests under the key, flagged as a replay.
     */
    RELEASE_TRANSIENT;

    /**
     * @param failureType the failure's classification, may be {@code null}
     * @return whether the key is released after a failure of this type
     */
    public boolean releases(FailureType failureType) {
        return switch (this) {
            case RELEASE_ALL -> true;
            case KEEP -> false;
            case RELEASE_TRANSIENT -> failureType == FailureType.TRANSIENT;
        };
    }

    /**
     * @return whether a failure this policy keeps is replayed to later
     *         requests (otherwise they get HTTP 409)
     */
    public boolean replaysKeptFailures() {
        return this == RELEASE_TRANSIENT;
    }
}
