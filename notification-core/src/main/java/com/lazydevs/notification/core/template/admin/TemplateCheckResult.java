package com.lazydevs.notification.core.template.admin;

import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.template.TemplateProblem;

import java.util.List;

/**
 * Outcome of a preview or a validation (DD-38).
 *
 * @param ok       whether the source rendered without problems
 * @param subject  rendered email subject, {@code null} for other channels or on failure
 * @param html     rendered HTML body, {@code null} when the body is plain text or on failure
 * @param text     rendered text: the email's plain-text part, the push body, or the whole
 *                 message for SMS; {@code null} on failure
 * @param payload  a channel renderer's structured content, for example the push title, body
 *                 and data; {@code null} when there is none or on failure
 * @param problems what is wrong; empty when {@code ok}
 * @since 1.3.0
 */
public record TemplateCheckResult(boolean ok, String subject, String html, String text, Object payload,
                                  List<TemplateProblem> problems) {

    static TemplateCheckResult failed(List<TemplateProblem> problems) {
        return new TemplateCheckResult(false, null, null, null, null, List.copyOf(problems));
    }

    static TemplateCheckResult rendered(RenderedContent content) {
        return new TemplateCheckResult(true, content.subject(), content.htmlBody(), content.textBody(),
                content.payload(), List.of());
    }
}
