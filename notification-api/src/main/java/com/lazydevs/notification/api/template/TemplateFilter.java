package com.lazydevs.notification.api.template;

/**
 * Narrows {@link TemplateStore#summaries}; a {@code null} part matches everything.
 *
 * @param channel    channel, lower case
 * @param templateId template id
 * @since 1.3.0
 */
public record TemplateFilter(String channel, String templateId) {

    /** Matches every key. */
    public static TemplateFilter all() {
        return new TemplateFilter(null, null);
    }

    /** Whether {@code key} passes this filter. */
    public boolean matches(TemplateKey key) {
        return (channel == null || channel.equalsIgnoreCase(key.channel()))
                && (templateId == null || templateId.equals(key.templateId()));
    }
}
