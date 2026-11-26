package com.lazydevs.notification.api.audit;

import com.lazydevs.notification.api.model.NotificationAudit;
import com.lazydevs.notification.api.model.NotificationResponse;

import java.util.List;
import java.util.Optional;

/**
 * Storage SPI for audit records (DD-33), the persistence behind the
 * status view ({@code NotificationService.find}).
 *
 * <p>With {@code notification.audit.enabled=true} the core audit service
 * builds each {@link NotificationAudit} and writes it through the store
 * selected by {@code notification.store.type}: an in-memory store
 * ({@code memory}) or {@code JdbcNotificationAuditStore}
 * ({@code jdbc}, notification-store-jdbc). An application can declare its
 * own bean of this type instead.
 *
 * <p>Records are keyed by {@code (tenantId, requestId)}; a {@code null}
 * tenant is one scope of its own. Records never hold recipient addresses:
 * {@link NotificationAudit#getRecipientSummary()} is masked and
 * {@link NotificationAudit#getRecipientCount()} is a count.
 *
 * <p>Implementations must be thread-safe. Write failures may propagate:
 * the audit service logs and swallows them so a send never fails because
 * of its audit record.
 *
 * @since 1.2.1
 */
public interface NotificationAuditStore {

    /**
     * Store the record of a received request. A record with the same
     * {@code (tenantId, requestId)} is replaced.
     *
     * @param audit the record, never {@code null}
     */
    void insert(NotificationAudit audit);

    /**
     * Apply the outcome of a send to the record
     * {@code (outcome.tenantId(), outcome.requestId())}: status, provider
     * (when the outcome names one), provider message id, error code, error
     * message, failure type, processed and sent timestamps, {@code failedAt}
     * (the outcome's {@code processedAt} for {@code FAILED} and
     * {@code REJECTED}, cleared otherwise) and {@code updatedAt} (now).
     * No record, no change.
     *
     * @param outcome the outcome, its error message already scrubbed
     * @return whether a record was updated
     */
    boolean recordOutcome(NotificationResponse outcome);

    /**
     * @return the record of {@code requestId} in {@code tenantId}
     */
    Optional<NotificationAudit> find(String tenantId, String requestId);

    /**
     * The most recently received record with this idempotency key. Under
     * {@code failure-policy=RELEASE_ALL} several requests can have used the
     * same key in turn; the latest is the one that holds it.
     *
     * @param callerId may be {@code null}, which is a scope of its own
     */
    Optional<NotificationAudit> findByIdempotencyKey(String tenantId, String callerId, String idempotencyKey);

    /**
     * The most recent {@code limit} records of a tenant, most recent first
     * (DD-20).
     */
    List<NotificationAudit> findRecent(String tenantId, int limit);
}
