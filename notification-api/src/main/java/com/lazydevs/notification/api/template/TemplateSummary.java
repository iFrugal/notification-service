package com.lazydevs.notification.api.template;

/**
 * What the store holds for one key, without content (DD-38).
 *
 * @param key       the template
 * @param published published override, without content; {@code null} when none
 * @param draft     draft, without content; {@code null} when none
 * @since 1.3.0
 */
public record TemplateSummary(TemplateKey key, TemplateVersion published, TemplateVersion draft) {
}
