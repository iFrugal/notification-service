package com.lazydevs.notification.api.template;

/**
 * Where a template the engine rendered came from (DD-38).
 *
 * @since 1.3.0
 */
public enum TemplateOrigin {
    /** A {@link TemplateSource}, by default the files under {@code notification.template.base-path}. */
    BUILT_IN,
    /** The published version of a {@link TemplateStore} override. */
    STORE
}
