package com.lazydevs.notification.core.store;

import java.util.Locale;

/**
 * The store family that backs the stateful notification features
 * (idempotency, rate limiting, dead letters and delivery events).
 *
 * <p>Selected globally by {@code notification.store.type} and overridable
 * per feature with the {@code notification.redis.<feature>.enabled} toggles.
 * Every family except {@link #MEMORY} lives in its own module; the marker
 * class is that module's auto-configuration, so its presence on the
 * classpath proves the module is there.
 */
public enum StoreType {

    /** In-process stores from notification-core. Always available. */
    MEMORY(null, null),

    /** Redis-backed stores from notification-redis. */
    REDIS("com.lazydevs.notification.redis.autoconfigure.NotificationRedisAutoConfiguration",
            "notification-redis"),

    /** JDBC-backed stores from notification-store-jdbc. */
    JDBC("com.lazydevs.notification.store.jdbc.JdbcStoreAutoConfiguration",
            "notification-store-jdbc");

    private final String markerClassName;
    private final String artifactId;

    StoreType(String markerClassName, String artifactId) {
        this.markerClassName = markerClassName;
        this.artifactId = artifactId;
    }

    /**
     * @return the fully qualified name of a class that is on the classpath
     *         exactly when this family's module is, or {@code null} for
     *         {@link #MEMORY}, which needs no extra module
     */
    public String markerClassName() {
        return markerClassName;
    }

    /**
     * @return the Maven artifactId (groupId {@code com.github.ifrugal}) of
     *         the module providing this family, or {@code null} for {@link #MEMORY}
     */
    public String artifactId() {
        return artifactId;
    }

    /** @return {@code true} when this family needs a module beyond notification-core */
    public boolean requiresModule() {
        return markerClassName != null;
    }

    /** @return the lower-case configuration value, for example {@code redis} */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
