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
     * Template store settings (DD-38), used when {@code notification.template.store.type=jdbc}.
     *
     * @since 1.3.0
     */
    private Template template = new Template();

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

    /**
     * Template store settings for databases with row-level security (DD-38). A session
     * that only sees its own tenant's rows cannot list other tenants' overrides or see
     * their changes; name a {@code SECURITY DEFINER} function for each, returning keys and
     * metadata only, and the store calls it instead of reading the tables.
     *
     * @since 1.3.0
     */
    @Data
    public static class Template {

        /**
         * Function {@code (p_channel varchar, p_template_id varchar)} returning the summary
         * columns of drafts and published versions across tenants. Blank reads the table.
         */
        private String summaryFunction;

        /**
         * Function {@code (p_after bigint, p_limit integer)} returning
         * {@code (id, tenant_id, channel, template_id, locale)} of the events after
         * {@code p_after}, by id. Blank reads the table.
         */
        private String changeFunction;
    }
}
