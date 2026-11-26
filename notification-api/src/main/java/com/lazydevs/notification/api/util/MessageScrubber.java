package com.lazydevs.notification.api.util;

import java.util.regex.Matcher;

/**
 * Removes recipient data from free text before the library persists it.
 *
 * <p>Error messages come from providers and SDKs and can quote the
 * recipient. Since 1.3.0 every error message the library persists (the
 * idempotency record's stored response, the audit record and therefore the
 * status view) first passes through a scrubber.
 *
 * <p>The default, {@link #placeholders()}, replaces every email address with
 * {@value #EMAIL_PLACEHOLDER} and every E.164-looking number (seven or more
 * digits, optional {@code +}) with {@value #PHONE_PLACEHOLDER}, using the
 * same patterns as {@link PiiMasking#redact(String)}.
 * Register a {@code MessageScrubber} bean to replace it, for example to also
 * remove names, or to drop messages entirely ({@code message -> null}).
 *
 * <p>Implementations must be thread-safe, must accept {@code null} and must
 * not throw.
 *
 * @since 1.3.0
 */
@FunctionalInterface
public interface MessageScrubber {

    /** Replacement for an email address. */
    String EMAIL_PLACEHOLDER = "[email]";

    /** Replacement for a phone number. */
    String PHONE_PLACEHOLDER = "[phone]";

    /**
     * Scrub one message.
     *
     * @param message the message, may be {@code null}
     * @return the scrubbed message; {@code null} drops it
     */
    String scrub(String message);

    /**
     * The default scrubber: email addresses and phone numbers become
     * placeholders. Applying it twice gives the same result as once.
     *
     * @return the scrubber
     */
    static MessageScrubber placeholders() {
        return PlaceholderScrubber.INSTANCE;
    }

    /** The {@link #placeholders()} implementation. */
    enum PlaceholderScrubber implements MessageScrubber {
        INSTANCE;

        @Override
        public String scrub(String message) {
            if (message == null || message.isEmpty()) {
                return message;
            }
            String out = PiiMasking.EMAIL.matcher(message).replaceAll(Matcher.quoteReplacement(EMAIL_PLACEHOLDER));
            return PiiMasking.PHONE.matcher(out).replaceAll(Matcher.quoteReplacement(PHONE_PLACEHOLDER));
        }
    }
}
