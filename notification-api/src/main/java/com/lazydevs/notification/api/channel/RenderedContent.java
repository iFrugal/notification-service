package com.lazydevs.notification.api.channel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Rendered notification content from the template engine.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenderedContent {

    /**
     * Subject line (for email, push title)
     */
    private String subject;

    /**
     * Plain text body
     */
    private String textBody;

    /**
     * HTML body (for email)
     */
    private String htmlBody;

    /**
     * Template ID that was used
     */
    private String templateId;

    /**
     * Create simple text content
     */
    public static RenderedContent text(String body) {
        return RenderedContent.builder()
                .textBody(body)
                .build();
    }

    /**
     * Create email content with subject and HTML
     */
    public static RenderedContent email(String subject, String htmlBody, String textBody) {
        return RenderedContent.builder()
                .subject(subject)
                .htmlBody(htmlBody)
                .textBody(textBody)
                .build();
    }

    /**
     * Create email content with subject and HTML only
     */
    public static RenderedContent emailHtml(String subject, String htmlBody) {
        return RenderedContent.builder()
                .subject(subject)
                .htmlBody(htmlBody)
                .build();
    }

    /**
     * Get the best available body (HTML preferred for email, text otherwise)
     */
    public String getPreferredBody() {
        return htmlBody != null ? htmlBody : textBody;
    }

    /**
     * Check if HTML content is available
     */
    public boolean hasHtml() {
        return htmlBody != null && !htmlBody.isBlank();
    }

    /**
     * Check if text content is available
     */
    public boolean hasText() {
        return textBody != null && !textBody.isBlank();
    }
}
