package com.lazydevs.notification.api.push;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validation shared by {@link PushDeviceRegistration} and {@link PushDevice}.
 * The limits match the columns of the JDBC registry, so a value that passes
 * here always fits.
 */
final class PushFields {

    static final int MAX_ID_LENGTH = 255;
    static final int MAX_TOKEN_LENGTH = 4096;
    static final int MAX_LOCALE_LENGTH = 35;
    static final int MAX_TIMEZONE_LENGTH = 64;

    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private PushFields() {
    }

    /** {@code value} stripped; blank or longer than {@code max} fails. */
    static String required(String name, String value, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return optional(name, value, max);
    }

    /**
     * A required identifier, such as a user or installation id: {@code value} stripped; blank,
     * longer than {@code max} or containing a control character fails. Control characters would
     * make keys and log lines ambiguous.
     */
    static String identifier(String name, String value, int max) {
        String stripped = required(name, value, max);
        if (CONTROL.matcher(stripped).find()) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        return stripped;
    }

    /** {@code value} stripped, {@code null} when blank; longer than {@code max} fails. */
    static String optional(String name, String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() > max) {
            throw new IllegalArgumentException(name + " must be at most " + max + " characters, got "
                    + stripped.length());
        }
        return stripped;
    }

    /** An immutable copy without {@code null} keys or values; empty for {@code null}. */
    static Map<String, String> attributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        attributes.forEach((key, value) -> {
            if (key == null || value == null) {
                throw new IllegalArgumentException("attributes must not contain null keys or values");
            }
        });
        return Map.copyOf(attributes);
    }
}
