package com.lazydevs.notification.api.channel;

import java.util.Objects;
import java.util.Optional;

/**
 * Rendered notification content from the template engine.
 *
 * @param subject    subject line (used for email and push notification title)
 * @param textBody   plain-text body
 * @param htmlBody   HTML body (used for email)
 * @param templateId template ID that was used to render this content
 * @param payload    channel-specific structured content produced by a
 *                   per-channel content renderer, for example a resolved
 *                   WhatsApp template message; {@code null} for content
 *                   rendered by the template engine (since 1.4.0)
 */
public record RenderedContent(
        String subject,
        String textBody,
        String htmlBody,
        String templateId,
        Object payload) {

    /**
     * Content without a structured payload, as rendered by the template engine.
     * The constructor every release before 1.4.0 had.
     */
    public RenderedContent(String subject, String textBody, String htmlBody, String templateId) {
        this(subject, textBody, htmlBody, templateId, null);
    }

    /**
     * The structured payload, when it is of the given type.
     *
     * @param type the expected payload type
     * @param <T>  the expected payload type
     * @return the payload; empty when there is none or it is of another type
     * @since 1.4.0
     */
    public <T> Optional<T> payload(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return type.isInstance(payload) ? Optional.of(type.cast(payload)) : Optional.empty();
    }

    /**
     * Create simple text content.
     */
    public static RenderedContent text(String body) {
        return new RenderedContent(null, body, null, null);
    }

    /**
     * Create email content with subject, HTML body, and text body.
     */
    public static RenderedContent email(String subject, String htmlBody, String textBody) {
        return new RenderedContent(subject, textBody, htmlBody, null);
    }

    /**
     * Create email content with subject and HTML body only.
     */
    public static RenderedContent emailHtml(String subject, String htmlBody) {
        return new RenderedContent(subject, null, htmlBody, null);
    }

    /**
     * Get the best available body — HTML preferred for email, plain text otherwise.
     */
    public String preferredBody() {
        return htmlBody != null ? htmlBody : textBody;
    }

    /**
     * @return {@code true} if HTML content is available.
     */
    public boolean hasHtml() {
        return htmlBody != null && !htmlBody.isBlank();
    }

    /**
     * @return {@code true} if text content is available.
     */
    public boolean hasText() {
        return textBody != null && !textBody.isBlank();
    }
}
