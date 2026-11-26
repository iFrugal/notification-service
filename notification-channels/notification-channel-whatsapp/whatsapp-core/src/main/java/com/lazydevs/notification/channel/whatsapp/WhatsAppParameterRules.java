package com.lazydevs.notification.channel.whatsapp;

import com.lazydevs.notification.api.exception.TemplateValidationException;

import java.time.temporal.TemporalAccessor;
import java.util.regex.Pattern;

/**
 * WhatsApp's rules for a template text parameter (DD-35), checked before a
 * request reaches the provider, so a bad value is a client error rather than a
 * provider rejection:
 * <ul>
 *   <li>present and not blank;</li>
 *   <li>no new line, carriage return or tab;</li>
 *   <li>no run of {@value #MAX_CONSECUTIVE_SPACES_PLUS_ONE} or more spaces;</li>
 *   <li>at most {@value #MAX_LENGTH} characters.</li>
 * </ul>
 * A value may be text, a number, a boolean, an enum or a date/time; anything
 * else, such as a list or a map, is rejected. Error messages name the template
 * and the variable, never the value.
 *
 * @since 1.4.0
 */
public final class WhatsAppParameterRules {

    /** Longest accepted parameter value. */
    public static final int MAX_LENGTH = 1024;

    /** A run of this many spaces is rejected. */
    public static final int MAX_CONSECUTIVE_SPACES_PLUS_ONE = 5;

    private static final Pattern SPACE_RUN = Pattern.compile(" {" + MAX_CONSECUTIVE_SPACES_PLUS_ONE + ",}");

    private WhatsAppParameterRules() {
    }

    /**
     * Check one value and return it as text.
     *
     * @param templateId the logical template id, for the error message
     * @param variable   the variable name, for the error message
     * @param value      the value from the request
     * @return the value as text
     * @throws TemplateValidationException when the value breaks a rule
     */
    public static String check(String templateId, String variable, Object value) {
        String text = asText(templateId, variable, value);
        if (text == null || text.isBlank()) {
            throw invalid(templateId, variable, "is missing or blank");
        }
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\t') >= 0) {
            throw invalid(templateId, variable, "contains a new line or a tab");
        }
        if (SPACE_RUN.matcher(text).find()) {
            throw invalid(templateId, variable, "contains " + MAX_CONSECUTIVE_SPACES_PLUS_ONE
                    + " or more consecutive spaces");
        }
        if (text.length() > MAX_LENGTH) {
            throw invalid(templateId, variable, "is longer than " + MAX_LENGTH + " characters");
        }
        return text;
    }

    private static String asText(String templateId, String variable, Object value) {
        return switch (value) {
            case null -> null;
            case CharSequence s -> s.toString();
            case Number n -> n.toString();
            case Boolean b -> b.toString();
            case Character c -> c.toString();
            case Enum<?> e -> e.name();
            case TemporalAccessor t -> t.toString();
            default -> throw invalid(templateId, variable, "is not a text value ("
                    + value.getClass().getSimpleName() + ")");
        };
    }

    private static TemplateValidationException invalid(String templateId, String variable, String problem) {
        return new TemplateValidationException("WhatsApp template '" + templateId + "': variable '" + variable
                + "' " + problem);
    }
}
