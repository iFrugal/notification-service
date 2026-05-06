package com.lazydevs.notification.api.delivery;

/**
 * Provider-side delivery state for a notification (DD-16).
 *
 * <p>Distinct from {@link com.lazydevs.notification.api.NotificationStatus}
 * — that enum tracks the <em>request lifecycle</em>
 * ({@code PENDING → PROCESSING → SENT/FAILED/REJECTED}) up to the point
 * where the provider returns a {@code providerMessageId}. Delivery
 * happens <em>after</em> the request lifecycle ends from the service's
 * perspective. Conflating them would explode the existing enum
 * combinatorially and break the {@code response.status() == FAILED}
 * switches in the retry / DLQ path.
 *
 * <p>Webhooks ingested under {@code /webhooks/{provider}/...} are
 * normalised onto these values per the provider mapping documented in
 * each handler.
 */
public enum DeliveryStatus {

    /**
     * Provider confirms the message reached the recipient device or
     * inbox. Mapping: SES {@code "Delivery"}, Twilio {@code "delivered"}.
     */
    DELIVERED,

    /**
     * Hard bounce — recipient address is invalid or undeliverable. Soft
     * bounces (mailbox full, transient) are mapped to
     * {@link #FAILED_AT_PROVIDER} since they may resolve on a re-send.
     * Mapping: SES {@code "Bounce"} where {@code bounceType=Permanent},
     * Twilio {@code "undelivered"} with permanent error codes.
     */
    BOUNCED,

    /**
     * Recipient flagged the message as spam / unwanted. SES surfaces
     * this as a "Complaint" event; Twilio doesn't have a direct
     * equivalent on SMS but FCM does on push (mapped to this once
     * push delivery callbacks land in a follow-up).
     */
    COMPLAINED,

    /**
     * Provider gave up after its own retries (carrier rejection on SMS,
     * SES message-rejected for content reasons, etc.). Operationally
     * different from {@link #BOUNCED} — bounce blames the recipient
     * address, this blames the path or the content.
     */
    FAILED_AT_PROVIDER,

    /**
     * Provider sent something we couldn't classify. Surface this to
     * operators in raw form so the next handler iteration can map
     * it correctly — better to log than to drop.
     */
    UNKNOWN
}
