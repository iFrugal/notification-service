package com.lazydevs.notification.store.jdbc;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the JDBC-backed stores, bound under
 * {@code notification.store.jdbc.*}.
 *
 * <p>The stores also read three settings owned by the rest of the
 * library, so the operator-facing surface stays the same whichever
 * backend is selected:
 * <ul>
 *   <li>{@code notification.idempotency.ttl} (default {@code PT24H}) for
 *       idempotency row expiry,</li>
 *   <li>{@code notification.dead-letter.max-entries} (default 1000) as the
 *       dead-letter snapshot bound,</li>
 *   <li>{@code notification.delivery-events.max-entries} (default 5000) as
 *       the delivery-event snapshot and lookup bound.</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "notification.store.jdbc")
public class JdbcStoreProperties {

    /** Database schema that holds the tables. {@code null} or blank uses the connection's search path. */
    private String schema;

    /** Prefix prepended to every table and index name. */
    private String tablePrefix = "notification_";

    /** Name of the DataSource bean to use. Blank selects the application's single (or primary) DataSource. */
    private String datasourceBeanName;

    /** SQL dialect. Only PostgreSQL is implemented. */
    private JdbcStoreDialect dialect = JdbcStoreDialect.POSTGRESQL;

    /** How long a dead-letter row is kept before it counts as expired and becomes eligible for purge. */
    private Duration deadLetterRetention = Duration.ofDays(30);

    /** How long a delivery-event row is kept before it counts as expired and becomes eligible for purge. */
    private Duration deliveryEventRetention = Duration.ofDays(30);

    /** Background purge of expired rows. */
    private Purge purge = new Purge();

    /**
     * Background purge settings. Off by default: many hosts prefer to run
     * the purge from their own scheduler (or {@code pg_cron}) by calling
     * {@link JdbcStorePurger#purgeAll()}.
     */
    @Data
    public static class Purge {

        /** Run a module-owned scheduled purge of expired rows. */
        private boolean enabled = false;

        /** Delay between purge runs. */
        private Duration interval = Duration.ofHours(1);

        /** Maximum rows deleted per statement. */
        private int batchSize = 1000;
    }
}
