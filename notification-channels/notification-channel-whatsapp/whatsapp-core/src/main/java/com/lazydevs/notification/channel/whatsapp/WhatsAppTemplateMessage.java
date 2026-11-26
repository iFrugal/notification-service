package com.lazydevs.notification.channel.whatsapp;

import java.util.List;
import java.util.Objects;

/**
 * A fully resolved WhatsApp template message: what a WhatsApp provider sends
 * (DD-35).
 *
 * <p>The {@code WHATSAPP} content renderer puts it into
 * {@link com.lazydevs.notification.api.channel.RenderedContent#payload()};
 * providers read it with
 * {@code content.payload(WhatsAppTemplateMessage.class)}. Every parameter has
 * passed {@link WhatsAppParameterRules}. Vendor-neutral: a provider maps it
 * onto its own API.
 *
 * <p>{@link #toString()} shows the template and the parameter counts, never
 * the values, which may be personal data.
 *
 * @param name             the approved template name
 * @param language         the template language code
 * @param headerParameters header text parameters, in placeholder order
 * @param bodyParameters   body text parameters, in placeholder order
 * @param buttons          dynamic button parameters
 * @since 1.3.0
 */
public record WhatsAppTemplateMessage(
        String name,
        String language,
        List<String> headerParameters,
        List<String> bodyParameters,
        List<ButtonParameter> buttons) {

    /**
     * Copies the lists.
     */
    public WhatsAppTemplateMessage {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(language, "language");
        headerParameters = headerParameters == null ? List.of() : List.copyOf(headerParameters);
        bodyParameters = bodyParameters == null ? List.of() : List.copyOf(bodyParameters);
        buttons = buttons == null ? List.of() : List.copyOf(buttons);
    }

    /**
     * The value of one dynamic button.
     *
     * @param index   the button's position in the template
     * @param subType the button sub type, lower case, for example {@code url}
     * @param value   the value: a URL suffix, a payload or a coupon code
     */
    public record ButtonParameter(int index, String subType, String value) {

        /**
         * Checks the arguments.
         */
        public ButtonParameter {
            Objects.requireNonNull(subType, "subType");
            Objects.requireNonNull(value, "value");
        }

        /** Never the value. */
        @Override
        public String toString() {
            return "ButtonParameter[index=" + index + ", subType=" + subType + "]";
        }
    }

    /** Never the parameter values. */
    @Override
    public String toString() {
        return "WhatsAppTemplateMessage[name=" + name + ", language=" + language
                + ", header=" + headerParameters.size() + ", body=" + bodyParameters.size()
                + ", buttons=" + buttons + "]";
    }
}
