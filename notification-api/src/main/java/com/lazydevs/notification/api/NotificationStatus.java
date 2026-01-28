package com.lazydevs.notification.api;

/**
 * Status of a notification request.
 */
public enum NotificationStatus {
    /**
     * Request received and queued for processing
     */
    ACCEPTED,

    /**
     * Currently being processed
     */
    PROCESSING,

    /**
     * Successfully sent to provider
     */
    SENT,

    /**
     * Confirmed delivered (if provider supports delivery confirmation)
     */
    DELIVERED,

    /**
     * Failed to send
     */
    FAILED,

    /**
     * Validation failed, request rejected
     */
    REJECTED
}
