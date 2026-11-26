package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.model.NotificationAudit;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;

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
     * <p>Never store the raw recipient: set
     * {@link NotificationAudit#getRecipientSummary() recipientSummary} to
     * {@link com.lazydevs.notification.api.util.PiiMasking#mask(com.lazydevs.notification.api.model.Recipient)
     * PiiMasking.mask(request.getRecipient())}.
     * When the returned record has no summary, {@code DefaultNotificationService}
     * fills it in on that instance.
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
     * Record the outcome of a send: its final status, provider, provider
     * message id, error and failure classification. The response's
     * {@code errorMessage} has already been through the configured
     * {@code MessageScrubber}.
     *
     * <p>Called by {@code DefaultNotificationService} once per send, and for
     * an idempotency record settled by the in-progress timeout (DD-32), whose
     * {@code requestId} is the original request's. The default delegates to
     * {@link #updateStatus}, so implementations written for 1.2.0 keep
     * working; override it to keep the fields {@code updateStatus} cannot
     * carry.
     *
     * @param response the outcome, never {@code null}
     * @since 1.3.0
     */
    default void recordOutcome(NotificationResponse response) {
        updateStatus(response.requestId(), response.status(), response.providerMessageId(),
                response.errorCode(), response.errorMessage());
    }

    /**
     * Find audit record by request ID.
     *
     * @param requestId the request ID
     * @return the audit record if found
     */
    Optional<NotificationAudit> findByRequestId(String requestId);

    /**
     * Find the audit record of {@code requestId} in {@code tenantId}
     * (DD-33). The default narrows {@link #findByRequestId(String)} to the
     * tenant; implementations backed by a store override it.
     *
     * @param tenantId  the tenant
     * @param requestId the request ID
     * @return the audit record if found
     * @since 1.3.0
     */
    default Optional<NotificationAudit> findByRequestId(String tenantId, String requestId) {
        return findByRequestId(requestId).filter(a -> java.util.Objects.equals(tenantId, a.getTenantId()));
    }

    /**
     * Find the most recent audit record sent under an idempotency key
     * (DD-33). The default returns empty.
     *
     * @param tenantId       the tenant
     * @param callerId       the calling service, may be {@code null}
     * @param idempotencyKey the key
     * @return the audit record if found
     * @since 1.3.0
     */
    default Optional<NotificationAudit> findByIdempotencyKey(String tenantId, String callerId,
                                                             String idempotencyKey) {
        return Optional.empty();
    }

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
