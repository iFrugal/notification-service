package com.lazydevs.notification.api.template;

/**
 * Told when a stored template changes, so caches of it can be dropped (DD-38). The engine
 * and the store-backed WhatsApp registry implement it.
 *
 * <p>Calls come from the thread that published the change in this process and from the
 * change feed's thread for changes made elsewhere; implementations must be thread-safe
 * and quick.
 *
 * @since 1.3.0
 */
@FunctionalInterface
public interface TemplateChangeListener {

    /**
     * The key changed. A default-layer key ({@code tenantId == null}) affects every tenant
     * that falls back to it.
     */
    void templateChanged(TemplateKey key);

    /**
     * Changes may have been missed, for example while the feed could not reach the store;
     * drop everything cached.
     */
    default void allTemplatesChanged() {
        // nothing cached by default
    }
}
