package com.lazydevs.notification.core.template.store;

/**
 * Value of {@code notification.template.store.type} (DD-38).
 *
 * @since 1.3.0
 */
public enum TemplateStoreType {
    /** No template store: templates come from the {@code TemplateSource} only, as before 1.3.0. */
    NONE,
    /** {@link InMemoryTemplateStore}: lost on restart, not shared between replicas. */
    MEMORY,
    /** The JDBC store of {@code notification-store-jdbc}, with its polling change feed. */
    JDBC
}
