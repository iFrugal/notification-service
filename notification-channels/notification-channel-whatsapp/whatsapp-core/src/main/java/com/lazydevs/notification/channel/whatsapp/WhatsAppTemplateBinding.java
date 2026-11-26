package com.lazydevs.notification.channel.whatsapp;

import java.util.List;
import java.util.Locale;

/**
 * One logical template id bound to an approved WhatsApp template (DD-35).
 *
 * <p>Each list names the request's {@code templateData} keys that fill the
 * template's positional placeholders, in {@code {{1}}, {{2}}, ...} order.
 *
 * @param name     the template name approved at the provider, for example {@code order_shipped}
 * @param language the template language code, for example {@code en} or {@code en_US}
 * @param header   variables of the header text, in placeholder order; never {@code null}
 * @param body     variables of the body text, in placeholder order; never {@code null}
 * @param buttons  dynamic button parameters; never {@code null}
 * @since 1.3.0
 */
public record WhatsAppTemplateBinding(
        String name,
        String language,
        List<String> header,
        List<String> body,
        List<Button> buttons) {

    /**
     * Validates and copies the arguments.
     *
     * @throws IllegalArgumentException when the name, the language or a variable is blank
     */
    public WhatsAppTemplateBinding {
        name = requireText(name, "name");
        language = requireText(language, "language");
        header = variables(header, "header");
        body = variables(body, "body");
        buttons = buttons == null ? List.of() : List.copyOf(buttons);
    }

    /**
     * The dynamic part of one button, for example the URL suffix of a
     * {@code url} button or the payload of a {@code quick_reply} button.
     *
     * @param index    the button's position in the template, 0 to 9
     * @param subType  the button sub type, lower case, for example {@code url},
     *                 {@code quick_reply} or {@code copy_code}
     * @param variable the {@code templateData} key that holds the value
     */
    public record Button(int index, String subType, String variable) {

        /** Largest button index WhatsApp accepts. */
        public static final int MAX_INDEX = 9;

        /**
         * Validates and normalises the arguments.
         *
         * @throws IllegalArgumentException when the index is out of range or a value is blank
         */
        public Button {
            if (index < 0 || index > MAX_INDEX) {
                throw new IllegalArgumentException("button index must be 0 to " + MAX_INDEX + ", got " + index);
            }
            subType = normaliseSubType(requireText(subType, "button sub-type"));
            variable = requireText(variable, "button variable");
        }
    }

    /**
     * Lower case, with {@code -} as {@code _}, so {@code quick-reply} and
     * {@code QUICK_REPLY} both read as {@code quick_reply}.
     */
    static String normaliseSubType(String subType) {
        return subType.strip().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
        return value.strip();
    }

    private static List<String> variables(List<String> names, String section) {
        if (names == null) {
            return List.of();
        }
        for (int i = 0; i < names.size(); i++) {
            requireText(names.get(i), section + " variable " + (i + 1));
        }
        return names.stream().map(String::strip).toList();
    }
}
