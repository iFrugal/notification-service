package com.lazydevs.notification.api.idempotency;

import com.lazydevs.notification.api.model.NotificationResponse;

import java.util.Optional;

/**
 * Storage SPI for idempotency records. The default in-process
 * implementation backs onto a Caffeine cache; an alternative
 * {@code RedisIdempotencyStore} is foreseen for multi-replica deployments
 * but is out of scope for this phase.
 *
 * <p>See {@code docs/design-decisions/10-idempotency.md} for the
 * end-to-end semantic contract this SPI implements.
 *
 * <p><strong>Threading.</strong> All methods MUST be safe for concurrent
 * invocation across threads — and, in distributed implementations, across
 * nodes. {@link #markInProgress(IdempotencyKey, String)} in particular
 * MUST be atomic so two callers racing for the same key cannot both
 * register their intent.
 */
public interface IdempotencyStore {

    /**
     * Look up an existing record for {@code key}.
     *
     * @return the record if one exists and has not been evicted by TTL or
     *         the implementation's bound; {@link Optional#empty()} otherwise.
     */
    Optional<IdempotencyRecord> findExisting(IdempotencyKey key);

    /**
     * Atomically register {@code key} as {@link IdempotencyStatus#IN_PROGRESS}.
     *
     * <p>Implementations MUST guarantee that for any concurrent set of
     * calls with the same key, exactly one returns {@code true}. The
     * loser receives {@code false} and can re-read with
     * {@link #findExisting(IdempotencyKey)} to obtain the winner's
     * notification id (e.g. for a 409 response).
     *
     * @param key            the composite scope.
     * @param notificationId the {@code requestId} claiming the key.
     * @return {@code true} if this caller won the race and the record was
     *         created; {@code false} if a record (in any status) already
     *         existed.
     */
    boolean markInProgress(IdempotencyKey key, String notificationId);

    /**
     * Record the terminal {@link NotificationResponse} against {@code key}.
     * The record's {@link IdempotencyStatus} transitions from
     * {@link IdempotencyStatus#IN_PROGRESS IN_PROGRESS} to
     * {@link IdempotencyStatus#COMPLETE COMPLETE}, and the response will
     * be replayed for duplicate requests until the implementation's TTL
     * elapses.
     *
     * <p>Callers MUST invoke this even on dispatch failure (with a
     * {@code FAILED} response) to release the {@code IN_PROGRESS} lock —
     * otherwise the key would remain locked until TTL expiry.
     */
    void markComplete(IdempotencyKey key, NotificationResponse response);

    /**
     * Remove records whose {@code recordedAt} timestamp is older than the
     * configured TTL.
     *
     * <p>Implementations that delegate eviction to the underlying store
     * (e.g. Caffeine's {@code expireAfterWrite}, Redis {@code EX}) MAY
     * implement this as a no-op. A default no-op is provided.
     */
    default void evictExpired() {
        // No-op by default; implementations with native TTL handle this.
    }
}
