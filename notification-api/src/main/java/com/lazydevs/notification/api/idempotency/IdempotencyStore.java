package com.lazydevs.notification.api.idempotency;

import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.util.MessageScrubber;

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
     * {@link #markInProgress(IdempotencyKey, String)} that also stores the
     * request fingerprint (DD-32) on the record, so a later request under the
     * same key can be compared with it. Every later record for the key
     * ({@link #markComplete}, {@link #settleInProgress}) keeps the
     * fingerprint.
     *
     * <p>The default ignores the fingerprint and delegates to
     * {@link #markInProgress(IdempotencyKey, String)}, so a store that does
     * not override it reports {@code null} fingerprints, which the service
     * treats as unknown and never compares.
     *
     * @param fingerprint SHA-256 hex fingerprint, may be {@code null}
     * @return as {@link #markInProgress(IdempotencyKey, String)}
     * @since 1.2.1
     */
    default boolean markInProgress(IdempotencyKey key, String notificationId, String fingerprint) {
        return markInProgress(key, notificationId);
    }

    /**
     * Settle an {@link IdempotencyStatus#IN_PROGRESS IN_PROGRESS} record that
     * has been in flight for too long (DD-32,
     * {@code notification.idempotency.in-progress-timeout}): it becomes
     * {@link IdempotencyStatus#COMPLETE COMPLETE} with {@code response}, in
     * its {@linkplain #storedForm(NotificationResponse) stored form}, and
     * keeps its notification id and fingerprint.
     *
     * <p>Implementations MUST make this a compare-and-set: the record changes
     * only if it is still {@code IN_PROGRESS} and still belongs to
     * {@code notificationId}. Of any number of concurrent callers at most one
     * gets {@code true}; a record that completed or was re-claimed in the
     * meantime is left alone.
     *
     * <p>The default returns {@code false} and changes nothing: the service
     * then keeps answering the key with a conflict, as in 1.2.0.
     *
     * @param key            the composite scope
     * @param notificationId the notification id the in-progress record must belong to
     * @param response       the terminal response to record
     * @return {@code true} if this call settled the record
     * @since 1.2.1
     */
    default boolean settleInProgress(IdempotencyKey key, String notificationId, NotificationResponse response) {
        return false;
    }

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
     * Remove the {@link IdempotencyStatus#COMPLETE COMPLETE} record for
     * {@code key}, but only if it still belongs to {@code notificationId}
     * (compare-and-delete). The service calls this after recording a
     * {@code FAILED} or {@code REJECTED} outcome, and before re-claiming a
     * key whose last outcome was a failure, so a retry under the same key
     * can win {@link #markInProgress(IdempotencyKey, String)} again (DD-10
     * "FAILED is fresh").
     *
     * <p>Implementations MUST make the check and the delete one atomic step,
     * and MUST NOT remove an {@link IdempotencyStatus#IN_PROGRESS IN_PROGRESS}
     * record or a record claimed by another notification id: either would
     * let a second caller win {@code markInProgress} while a send is in
     * flight.
     *
     * <p>The default returns {@code false} and changes nothing, which keeps
     * the 1.1.x behaviour for stores that do not implement it: the failed
     * record stays until its TTL elapses and a retry under the same key is
     * answered with a conflict.
     *
     * @param key            the composite scope.
     * @param notificationId the notification id the record must belong to.
     * @return {@code true} if the record was removed; {@code false} if there
     *         was no record, it belonged to another notification id, it was
     *         still in progress, or the store does not support release.
     * @since 1.1.2
     */
    default boolean release(IdempotencyKey key, String notificationId) {
        return false;
    }

    /**
     * The form of {@code response} a store persists in
     * {@link #markComplete(IdempotencyKey, NotificationResponse)}.
     *
     * <p>The {@code errorMessage} of a {@code FAILED} or {@code REJECTED}
     * response is free text from a provider and can carry recipient data, so
     * the stored copy never holds it raw: it passes through
     * {@link MessageScrubber#placeholders()}, which replaces email addresses
     * and phone numbers with placeholders. Every other response is returned
     * unchanged.
     *
     * <p>Stores call this as a safety net. {@code DefaultNotificationService}
     * already hands them the message removed (the 1.1.2 to 1.2.0 behaviour), or
     * scrubbed when {@code notification.idempotency.store-scrubbed-error-message}
     * is on (since 1.2.1).
     *
     * @param response the terminal response, may be {@code null}
     * @return the response to persist
     * @since 1.1.2
     */
    static NotificationResponse storedForm(NotificationResponse response) {
        return storedForm(response, MessageScrubber.placeholders());
    }

    /**
     * {@link #storedForm(NotificationResponse)} with a given scrubber. A
     * scrubber that returns {@code null} restores the 1.2.0 behaviour of
     * dropping the message.
     *
     * @param response the terminal response, may be {@code null}
     * @param scrubber the scrubber for the error message
     * @return the response to persist
     * @since 1.2.1
     */
    static NotificationResponse storedForm(NotificationResponse response, MessageScrubber scrubber) {
        if (response == null || response.errorMessage() == null
                || (response.status() != NotificationStatus.FAILED
                        && response.status() != NotificationStatus.REJECTED)) {
            return response;
        }
        return response.withErrorMessage(scrubber.scrub(response.errorMessage()));
    }

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
