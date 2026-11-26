package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.exception.IdempotencyInProgressException;
import com.lazydevs.notification.api.exception.IdempotencyKeyReusedException;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.idempotency.IdempotencyStatus;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.util.MessageScrubber;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.idempotency.IdempotencyFailurePolicy;
import com.lazydevs.notification.core.metrics.NotificationMetrics;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The idempotency part of {@link DefaultNotificationService#send} (DD-10,
 * DD-32): answering a key that already has a record, claiming a fresh key,
 * and closing the record once the send is over.
 */
@Slf4j
final class IdempotencyCoordinator {

    /** Error code of a response settled by the in-progress timeout. */
    static final String IN_PROGRESS_TIMEOUT = "IN_PROGRESS_TIMEOUT";

    private final IdempotencyStore store;
    private final NotificationProperties properties;
    private final NotificationAuditService auditService;
    private final Optional<NotificationMetrics> metrics;
    private final MessageScrubber scrubber;
    private final Clock clock;

    IdempotencyCoordinator(IdempotencyStore store, NotificationProperties properties,
                           NotificationAuditService auditService, Optional<NotificationMetrics> metrics,
                           MessageScrubber scrubber, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.auditService = auditService;
        this.metrics = metrics;
        this.scrubber = scrubber;
        this.clock = clock;
        NotificationProperties.IdempotencyProperties config = properties.getIdempotency();
        if (config != null && config.hasConflictingFailurePolicy()) {
            log.warn("notification.idempotency.failure-policy={} and the deprecated retry-after-failure"
                    + " disagree; failure-policy wins", config.getFailurePolicy());
        }
    }

    /**
     * Answer the request from an existing record, or claim the key for it.
     *
     * @param beforeClaim run once the request is known to need a real send,
     *                    right before the claim (the rate-limit check)
     * @return the response to replay; empty when the caller now holds the
     *         key and must dispatch
     * @throws IdempotencyInProgressException when another send holds the key
     * @throws IdempotencyKeyReusedException  when the key belongs to a
     *                                        different request and
     *                                        {@code reject-key-reuse} is on
     */
    Optional<NotificationResponse> claim(NotificationRequest request, IdempotencyKey key, String fingerprint,
                                         Runnable beforeClaim) {
        Optional<IdempotencyRecord> existing = store.findExisting(key);
        if (existing.isPresent()) {
            Optional<NotificationResponse> answer = answer(request, key, fingerprint, existing.get());
            if (answer.isPresent()) {
                return answer;
            }
        }
        beforeClaim.run();
        if (!store.markInProgress(key, request.getRequestId(), fingerprint)) {
            // Race lost: another caller registered between findExisting and markInProgress.
            IdempotencyRecord concurrent = store.findExisting(key)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency race lost but record not retrievable; should not happen"));
            rejectReuse(concurrent, fingerprint);
            throw new IdempotencyInProgressException(concurrent.notificationId());
        }
        return Optional.empty();
    }

    /**
     * Record the outcome of a send that held the key, then release the key
     * when the failure policy says so.
     *
     * @param refundUnsent called after a release under
     *                     {@link IdempotencyFailurePolicy#RELEASE_TRANSIENT},
     *                     when the failed attempt is known not to have sent anything
     */
    void close(NotificationRequest request, IdempotencyKey key, NotificationResponse outcome,
               Consumer<NotificationRequest> refundUnsent) {
        store.markComplete(key, IdempotencyStore.storedForm(outcome, scrubber));
        if (isReplayable(outcome.status())) {
            return;
        }
        IdempotencyFailurePolicy policy = policy();
        if (policy.releases(outcome.failureType())
                && releaseQuietly(key, request.getRequestId())
                && policy == IdempotencyFailurePolicy.RELEASE_TRANSIENT) {
            refundUnsent.accept(request);
        }
    }

    private Optional<NotificationResponse> answer(NotificationRequest request, IdempotencyKey key,
                                                  String fingerprint, IdempotencyRecord rec) {
        rejectReuse(rec, fingerprint);
        if (rec.status() == IdempotencyStatus.IN_PROGRESS) {
            return settleOrConflict(request, key, fingerprint, rec);
        }
        NotificationResponse cached = rec.response();
        if (cached == null) {
            // A COMPLETE record without a response cannot be replayed; treat it as a failure.
            releaseIfPolicyAllows(key, rec, null);
            return Optional.empty();
        }
        IdempotencyFailurePolicy policy = policy();
        if (isReplayable(cached.status())
                || isSettledTimeout(cached)
                || (!policy.releases(cached.failureType()) && policy.replaysKeptFailures())) {
            return Optional.of(replay(request, rec, cached));
        }
        log.debug("Idempotency key '{}' had a prior {} attempt; proceeding fresh.",
                sanitize(request.getIdempotencyKey()), cached.status());
        releaseIfPolicyAllows(key, rec, cached.failureType());
        return Optional.empty();
    }

    /**
     * A failed record left behind (by an older version, or because a
     * release failed) is released before the claim, so markInProgress can
     * win. Compare-and-delete on the failed attempt's id, so a concurrent
     * retry that already re-claimed the key keeps its claim.
     */
    private void releaseIfPolicyAllows(IdempotencyKey key, IdempotencyRecord rec, FailureType failureType) {
        if (policy().releases(failureType)) {
            releaseQuietly(key, rec.notificationId());
        }
    }

    private Optional<NotificationResponse> settleOrConflict(NotificationRequest request, IdempotencyKey key,
                                                            String fingerprint, IdempotencyRecord rec) {
        Duration timeout = properties.getIdempotency().getInProgressTimeout();
        Instant now = clock.instant();
        if (timeout == null || rec.recordedAt() == null || now.isBefore(rec.recordedAt().plus(timeout))) {
            throw new IdempotencyInProgressException(rec.notificationId());
        }
        NotificationResponse settled = timedOut(request, rec, timeout, now);
        if (store.settleInProgress(key, rec.notificationId(), IdempotencyStore.storedForm(settled, scrubber))) {
            log.warn("Idempotency key held IN_PROGRESS for longer than {}; settled as FAILED/AMBIGUOUS"
                            + " (the first attempt may have been delivered): notificationId={}",
                    timeout, sanitize(rec.notificationId()));
            recordSettledOutcome(settled);
            return Optional.of(replay(request, rec, settled));
        }
        // Lost the settle race, or the store cannot settle: answer from what is there now.
        IdempotencyRecord current = store.findExisting(key).orElse(null);
        if (current == null) {
            return Optional.empty();
        }
        if (current.status() == IdempotencyStatus.IN_PROGRESS) {
            throw new IdempotencyInProgressException(current.notificationId());
        }
        return answer(request, key, fingerprint, current);
    }

    /**
     * A record settled by the in-progress timeout is never released, whatever
     * the failure policy: the first attempt may have been delivered.
     */
    private static boolean isSettledTimeout(NotificationResponse cached) {
        return IN_PROGRESS_TIMEOUT.equals(cached.errorCode()) && cached.failureType() == FailureType.AMBIGUOUS;
    }

    private void recordSettledOutcome(NotificationResponse settled) {
        try {
            auditService.recordOutcome(settled);
        } catch (RuntimeException e) {
            log.warn("Audit update for a settled idempotency key failed: notificationId={}: {}",
                    sanitize(settled.requestId()), e.toString());
        }
    }

    private static NotificationResponse timedOut(NotificationRequest request, IdempotencyRecord rec,
                                                 Duration timeout, Instant now) {
        return new NotificationResponse(
                rec.notificationId(),
                null,
                request.getTenantId(),
                request.getCallerId(),
                request.getChannel(),
                null,
                NotificationStatus.FAILED,
                null,
                IN_PROGRESS_TIMEOUT,
                "The first attempt did not complete within " + timeout + "; it may have been delivered",
                rec.recordedAt(),
                now,
                null,
                null,
                FailureType.AMBIGUOUS,
                null);
    }

    private NotificationResponse replay(NotificationRequest request, IdempotencyRecord rec,
                                        NotificationResponse cached) {
        log.info("Idempotency replay: requestId={}, key={}, originalRequestId={}, status={}",
                sanitize(request.getRequestId()), sanitize(request.getIdempotencyKey()),
                sanitize(cached.requestId()), cached.status());
        auditService.recordDuplicateHit(request, rec);
        metrics.ifPresent(m -> m.recordIdempotencyReplay(request.getTenantId()));
        // Stamp as a replay so the controller can surface X-Idempotent-Replay: true (DD-10).
        return NotificationResponse.replayedFrom(cached);
    }

    private void rejectReuse(IdempotencyRecord rec, String fingerprint) {
        if (properties.getIdempotency().isRejectKeyReuse()
                && rec.fingerprint() != null
                && fingerprint != null
                && !rec.fingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReusedException(rec.notificationId());
        }
    }

    /**
     * Best-effort {@link IdempotencyStore#release}. A store error is logged
     * and swallowed: the failed record then simply stays, and the next
     * retry under the key tries the release again.
     *
     * @return whether the record was released
     */
    private boolean releaseQuietly(IdempotencyKey key, String notificationId) {
        try {
            boolean released = store.release(key, notificationId);
            log.debug("Idempotency release after failure: notificationId={}, released={}",
                    sanitize(notificationId), released);
            return released;
        } catch (RuntimeException e) {
            log.warn("Idempotency release failed for notificationId={}: {}",
                    sanitize(notificationId), e.toString());
            return false;
        }
    }

    private IdempotencyFailurePolicy policy() {
        return properties.getIdempotency().resolvedFailurePolicy();
    }

    /**
     * Statuses that count as a successful prior outcome; they are always
     * replayed and never released.
     */
    static boolean isReplayable(NotificationStatus status) {
        return status == NotificationStatus.SENT
                || status == NotificationStatus.DELIVERED
                || status == NotificationStatus.ACCEPTED;
    }

    private static String sanitize(String s) {
        return s == null ? "null" : s.replaceAll("\\p{Cntrl}", "_");
    }
}
