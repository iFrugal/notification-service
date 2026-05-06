package com.lazydevs.notification.api.delivery;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A single parsed and signature-verified delivery callback ingested
 * via {@code POST /webhooks/{provider}/...} (DD-16).
 *
 * @param timestamp         when the provider claims the event happened
 *                          (their clock — we trust it for ordering, not
 *                          for SLA arithmetic)
 * @param providerName      our internal provider id
 *                          ({@code "twilio"}, {@code "ses"}, etc.) —
 *                          matches {@code NotificationResponse.provider()}
 * @param providerMessageId the join key — matches
 *                          {@link com.lazydevs.notification.api.model.NotificationResponse#providerMessageId()}
 *                          set when the original send succeeded
 * @param providerEventId   provider-supplied dedup key. Same callback
 *                          arriving twice (provider retries) carries
 *                          the same id — listener implementations
 *                          should treat this as a unique key on
 *                          inbound. {@code null} permitted but
 *                          unusual; falling back to
 *                          {@code providerName + providerMessageId + status}
 *                          is a reasonable approximation
 * @param status            normalised delivery state
 * @param reason            provider-supplied error description.
 *                          {@code null} for success events. Free-form;
 *                          treat as opaque
 * @param attributes        the raw provider fields, lowercase-keyed,
 *                          for audit forensics. Never {@code null} —
 *                          empty map for events with no extra detail
 */
public record DeliveryEvent(
        Instant timestamp,
        String providerName,
        String providerMessageId,
        String providerEventId,
        DeliveryStatus status,
        String reason,
        Map<String, String> attributes) {

    public DeliveryEvent {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(providerName, "providerName");
        Objects.requireNonNull(providerMessageId, "providerMessageId");
        Objects.requireNonNull(status, "status");
        // Always present a non-null map to listeners — saves them a
        // null-check on every read. Defensive copy so callers can
        // mutate the source after construction.
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
