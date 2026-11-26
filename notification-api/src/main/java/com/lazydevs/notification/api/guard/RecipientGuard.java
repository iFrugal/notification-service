package com.lazydevs.notification.api.guard;

import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.Recipient;

import java.util.List;
import java.util.Objects;

/**
 * Decides, just before dispatch, which recipients a send may really reach
 * (DD-34). The typical use is a non-production environment that must only
 * ever mail or text an allow-list, with everything else redirected to a test
 * inbox or dropped.
 *
 * <p>{@code DefaultNotificationService} calls the guard once per send, after
 * the idempotency claim and with the request exactly as the caller sent it
 * (the fingerprint and the idempotency key are taken from that original
 * request). The decision's recipient replaces the original for the audit
 * record and the provider; the original addresses are never persisted. When
 * the decision blocks every recipient the send fails with
 * {@link #BLOCKED_ERROR_CODE} and failure type {@code PERMANENT}, without
 * reaching the provider.
 *
 * <p>The library's default is configuration driven
 * ({@code notification.recipient-guard.*}); declare a bean of this type to
 * replace it. Implementations must be thread-safe and must not change the
 * request.
 *
 * @since 1.2.1
 */
@FunctionalInterface
public interface RecipientGuard {

    /** Error code of a send whose every recipient was blocked. */
    String BLOCKED_ERROR_CODE = "RECIPIENT_BLOCKED";

    /**
     * Decide the recipients of one send.
     *
     * @param request the request as the caller sent it, never {@code null}
     * @return the decision, never {@code null}
     */
    Decision check(NotificationRequest request);

    /**
     * A guard that lets every recipient through, the behaviour without a
     * guard.
     *
     * @return the guard
     */
    static RecipientGuard allowAll() {
        return request -> Decision.unchanged(request.getRecipient());
    }

    /**
     * The outcome of {@link #check}.
     *
     * @param recipient           the recipient to send to; {@code null} when
     *                            every address was blocked
     * @param redirectedOriginals the original addresses that were replaced by
     *                            a redirect address, in order; never persisted
     * @param dropped             how many addresses were removed without a
     *                            replacement (including duplicates folded
     *                            into one)
     * @param subjectPrefix       text to put in front of the email subject,
     *                            or {@code null} for none
     */
    record Decision(Recipient recipient, List<String> redirectedOriginals, int dropped, String subjectPrefix) {

        public Decision {
            redirectedOriginals = redirectedOriginals == null ? List.of() : List.copyOf(redirectedOriginals);
        }

        /**
         * @param recipient the original recipient
         * @return a decision that changes nothing
         */
        public static Decision unchanged(Recipient recipient) {
            return new Decision(Objects.requireNonNull(recipient, "recipient"), List.of(), 0, null);
        }

        /** @return whether every recipient was blocked */
        public boolean blocked() {
            return recipient == null;
        }

        /** @return whether the guard redirected or dropped anything */
        public boolean changed() {
            return blocked() || !redirectedOriginals.isEmpty() || dropped > 0;
        }
    }
}
