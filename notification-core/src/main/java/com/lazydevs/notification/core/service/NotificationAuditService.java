package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.model.NotificationAudit;
import com.lazydevs.notification.api.model.NotificationRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Service interface for notification audit.
 * Implementations can use different storage backends via persistence-api.
 */
public interface NotificationAuditService {

    /**
     * Record that a notification request was received.
     *
     * @param request the notification request
     * @return the created audit record (may be null if audit is disabled)
     */
    NotificationAudit recordReceived(NotificationRequest request);

    /**
     * Update the status of a notification.
     *
     * @param requestId        the request ID
     * @param status           the new status
     * @param providerMessageId the provider message ID (if available)
     * @param errorCode        the error code (if failed)
     * @param errorMessage     the error message (if failed)
     * @return the updated audit record (may be null if audit is disabled)
     */
    NotificationAudit updateStatus(String requestId, NotificationStatus status,
                                    String providerMessageId, String errorCode, String errorMessage);

    /**
     * Find audit record by request ID.
     *
     * @param requestId the request ID
     * @return the audit record if found
     */
    Optional<NotificationAudit> findByRequestId(String requestId);

    /**
     * Check if audit is enabled.
     *
     * @return true if audit is enabled
     */
    boolean isEnabled();

    /**
     * Record that a request was deduplicated against a prior, completed
     * idempotent send (DD-10). The first attempt's full audit record is
     * preserved under the original {@code requestId}; this hook lets the
     * implementation also note the replay attempt so operators can trace
     * how often a key got hit.
     *
     * <p>Default implementation is a no-op so existing audit backends
     * don't have to update.
     */
    default void recordDuplicateHit(NotificationRequest replayRequest, IdempotencyRecord originalRecord) {
        // no-op
    }

    /**
     * Return the most-recent {@code limit} audit records for the given
     * tenant, ordered most-recent-first (DD-20).
     *
     * <p>Returns {@link Optional#empty()} when the backend can't
     * naturally answer the listing — same shape
     * {@code DeadLetterStore.snapshot()} and
     * {@code DeliveryEventStore.snapshot()} use. The default no-op
     * here keeps existing audit impls compiling; the
     * {@code GET /admin/audit/recent} admin endpoint renders the
     * empty case as a 200 with an explanatory message rather than
     * a 501 — operationally identical for an operator.
     *
     * @param tenantId required; cross-tenant recent is rejected at
     *                 the controller for blast-radius reasons
     * @param limit    1..200; the controller clamps before calling
     */
    default Optional<List<NotificationAudit>> findRecent(String tenantId, int limit) {
        return Optional.empty();
    }
}
