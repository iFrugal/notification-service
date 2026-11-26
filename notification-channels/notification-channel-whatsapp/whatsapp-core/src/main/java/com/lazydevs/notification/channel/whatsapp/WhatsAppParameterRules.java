package com.lazydevs.notification.channel.whatsapp;

import com.lazydevs.notification.api.exception.TemplateRenderException;
import com.lazydevs.notification.api.exception.TemplateRenderException.Reason;

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
 * else, such as a list or a map, is rejected. A violation is a
 * {@link TemplateRenderException}: {@link Reason#MODEL_INVALID} for a missing or
 * non-text value, {@link Reason#PARAMETER_INVALID} for a value that breaks a
 * rule. Its variable path is the variable, and its message names the template
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
     * @param templateId the logical template id, for the error
     * @param variable   the variable name or request field, the error's variable path
     * @param value      the value from the request
     * @return the value as text
     * @throws TemplateRenderException when the value is missing or breaks a rule
     */
    public static String check(String templateId, String variable, Object value) {
        String text = asText(templateId, variable, value);
        if (text == null) {
            throw invalid(Reason.MODEL_INVALID, templateId, variable, "is missing");
        }
        if (text.isBlank()) {
            throw invalid(Reason.PARAMETER_INVALID, templateId, variable, "is blank");
        }
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\t') >= 0) {
            throw invalid(Reason.PARAMETER_INVALID, templateId, variable, "contains a new line or a tab");
        }
        if (SPACE_RUN.matcher(text).find()) {
            throw invalid(Reason.PARAMETER_INVALID, templateId, variable, "contains "
                    + MAX_CONSECUTIVE_SPACES_PLUS_ONE + " or more consecutive spaces");
        }
        if (text.length() > MAX_LENGTH) {
            throw invalid(Reason.PARAMETER_INVALID, templateId, variable, "is longer than " + MAX_LENGTH
                    + " characters");
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
            default -> throw invalid(Reason.MODEL_INVALID, templateId, variable, "is not a text value ("
                    + value.getClass().getSimpleName() + ")");
        };
    }

    /**
     * A template rule violation: the variable is the exception's variable path, so a
     * caller can point at the field; the message names the template and the variable,
     * never the value.
     *
     * @param reason     {@link Reason#MODEL_INVALID} or {@link Reason#PARAMETER_INVALID}
     * @param templateId the logical template id
     * @param variable   the variable name or request field
     * @param problem    what is wrong, without the value
     * @return the exception to throw
     */
    static TemplateRenderException invalid(Reason reason, String templateId, String variable, String problem) {
        return new TemplateRenderException(reason, null, templateId, variable,
                "WhatsApp template '" + templateId + "': variable '" + variable + "' " + problem, null);
    }
}
