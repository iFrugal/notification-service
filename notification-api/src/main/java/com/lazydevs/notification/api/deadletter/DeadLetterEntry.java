package com.lazydevs.notification.api.deadletter;

import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;

import java.time.Instant;
import java.util.Objects;

/**
 * A single entry in the {@link DeadLetterStore} — a notification that
 * either exhausted its retries or was classified as a permanent
 * failure (DD-13).
 *
 * @param timestamp   when the entry was recorded
 * @param request     the original request — captured before any retry
 *                    so callers see exactly what the system tried to
 *                    send. Holds template data which may include PII;
 *                    the admin endpoint redacts before serialising.
 * @param response    the final {@link NotificationResponse} (always
 *                    {@code FAILED} status by definition)
 * @param attempts    how many retries were taken — {@code 1} for a
 *                    permanent failure that wasn't retried,
 *                    {@code maxAttempts} when retries were exhausted
 * @param failureType why the system gave up — {@link FailureType#PERMANENT}
 *                    means the predicate said "don't retry" early;
 *                    {@link FailureType#TRANSIENT} or
 *                    {@link FailureType#UNKNOWN} means retries were
 *                    exhausted
 */
public record DeadLetterEntry(
        Instant timestamp,
        NotificationRequest request,
        NotificationResponse response,
        int attempts,
        FailureType failureType) {

    public DeadLetterEntry {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(failureType, "failureType");
    }
}
