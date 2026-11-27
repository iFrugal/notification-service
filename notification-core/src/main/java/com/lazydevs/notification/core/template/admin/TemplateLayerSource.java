package com.lazydevs.notification.core.template.admin;

/**
 * Which layer a template resolves to (DD-38). The engine checks them in this order for a
 * tenant; the default layer only has the last two.
 *
 * @since 1.3.0
 */
public enum TemplateLayerSource {
    /** The tenant's published store override. */
    TENANT_OVERRIDE,
    /** The tenant's own built-in file. */
    TENANT_BUILT_IN,
    /** The default layer's published store override. */
    DEFAULT_OVERRIDE,
    /** The default built-in file. */
    DEFAULT_BUILT_IN,
    /** Nothing: a send would fail with {@code TEMPLATE_NOT_FOUND}. */
    NONE;

    /** Whether this is a built-in file rather than a store override. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isBuiltIn() {
        return this == TENANT_BUILT_IN || this == DEFAULT_BUILT_IN;
    }
}
