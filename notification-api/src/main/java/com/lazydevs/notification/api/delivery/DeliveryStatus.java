package com.lazydevs.notification.api.delivery;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

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
 *
 * <p>Constants are only ever appended, so their ordinals never change. A
 * reader that enables {@code READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE}
 * reads a constant it does not know as {@link #UNKNOWN}.
 *
 * <p>Ordering: providers do not guarantee that callbacks arrive in the order
 * the states happened. WhatsApp, for example, can report {@link #READ} before
 * {@link #DELIVERED}. A consumer that projects these events onto a current
 * status must never move it backwards: progress runs
 * {@link #SENT} &lt; {@link #DELIVERED} &lt; {@link #READ}, and a later
 * {@code SENT} or {@code DELIVERED} does not replace a {@code READ}.
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
     *
     * <p>Also published by a provider that learns from the send response
     * that the target no longer exists, such as an unregistered push device
     * token, with reason {@link DeliveryEvents#REASON_INVALID_TARGET}
     * (since 1.2.0).
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
     *
     * <p>Also the value a reader that enables
     * {@code READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE} uses for a
     * constant added by a later version (since 1.1.2).
     */
    @JsonEnumDefaultValue
    UNKNOWN,

    /**
     * The provider handed the message on towards the recipient, but has not
     * confirmed delivery yet. Mapping: Meta WhatsApp {@code "sent"}.
     *
     * <p>Readers from 1.2.x and earlier read it as {@link #UNKNOWN} when they
     * enable {@code READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE}.
     *
     * @since 1.4.0
     */
    SENT,

    /**
     * The recipient opened the message. Implies {@link #DELIVERED}, and may
     * arrive before the delivered callback. Mapping: Meta WhatsApp
     * {@code "read"}.
     *
     * <p>Readers from 1.2.x and earlier read it as {@link #UNKNOWN} when they
     * enable {@code READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE}.
     *
     * @since 1.4.0
     */
    READ
}
