package com.lazydevs.notification.api.channel;

/**
 * Rendered notification content from the template engine.
 *
 * @param subject    subject line (used for email and push notification title)
 * @param textBody   plain-text body
 * @param htmlBody   HTML body (used for email)
 * @param templateId template ID that was used to render this content
 */
public record RenderedContent(
        String subject,
        String textBody,
        String htmlBody,
        String templateId) {

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
