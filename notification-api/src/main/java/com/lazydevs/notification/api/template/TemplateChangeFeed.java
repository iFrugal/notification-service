package com.lazydevs.notification.api.template;

/**
 * Carries template changes made by other processes or replicas to this one (DD-38). The
 * process that publishes a change also tells its own listeners directly, so a feed only
 * has to cover the others.
 *
 * <p>The JDBC store ships a feed that polls the event table; other transports (Postgres
 * {@code LISTEN/NOTIFY}, Redis pub/sub) can implement this interface.
 *
 * @since 1.3.0
 */
public interface TemplateChangeFeed {

    /** Deliver every change seen from now on to {@code listener}. */
    void subscribe(TemplateChangeListener listener);
}
