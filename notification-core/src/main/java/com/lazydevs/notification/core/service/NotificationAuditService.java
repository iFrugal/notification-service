package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.model.NotificationAudit;
import com.lazydevs.notification.api.model.NotificationRequest;

import java.time.Instant;
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
}
