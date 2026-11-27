package com.lazydevs.notification.api.template;

/**
 * What a {@link TemplateEvent} records (DD-38).
 *
 * @since 1.3.0
 */
public enum TemplateEventAction {
    CREATED_DRAFT,
    UPDATED_DRAFT,
    PUBLISHED,
    RESTORED,
    DISCARDED,
    RESET,
    PROVIDER_STATUS
}
