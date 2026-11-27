package com.lazydevs.notification.api.template;

/**
 * Lifecycle of a stored template version (DD-38).
 *
 * @since 1.3.0
 */
public enum TemplateVersionStatus {
    /** The one editable draft of a key; version {@code 0} until it is published. */
    DRAFT,
    /** The live override; at most one per key. */
    PUBLISHED,
    /** Replaced by a later publish or restore. */
    SUPERSEDED,
    /** Taken out of service by a reset; the key falls back to the next layer. */
    ARCHIVED
}
