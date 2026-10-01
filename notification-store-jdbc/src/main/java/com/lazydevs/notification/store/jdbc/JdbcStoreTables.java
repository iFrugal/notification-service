package com.lazydevs.notification.store.jdbc;

import java.util.regex.Pattern;

/**
 * Resolved table and index names for the JDBC stores.
 *
 * <p>Names are interpolated into SQL, so both the schema and the prefix
 * are validated as plain unquoted SQL identifiers ({@code [A-Za-z_][A-Za-z0-9_]*});
 * anything else is rejected at startup rather than escaped. Unquoted
 * identifiers are case-folded by PostgreSQL, as usual.
 *
 * @param schema      schema qualifier, or {@code null} for the connection's search path
 * @param tablePrefix prefix for every table, constraint and index name; may be empty
 */
public record JdbcStoreTables(String schema, String tablePrefix) {

    /** Default prefix, matching {@code notification.store.jdbc.table-prefix}. */
    public static final String DEFAULT_PREFIX = "notification_";

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_]*");

    public JdbcStoreTables {
        schema = (schema == null || schema.isBlank()) ? null : schema.trim();
        tablePrefix = tablePrefix == null ? "" : tablePrefix.trim();
        if (schema != null && !IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException(
                    "notification.store.jdbc.schema must be a plain SQL identifier, got '" + schema + "'");
        }
        if (!PREFIX.matcher(tablePrefix).matches()
                || !IDENTIFIER.matcher(tablePrefix + "x").matches()) {
            throw new IllegalArgumentException(
                    "notification.store.jdbc.table-prefix must contain only letters, digits and '_'"
                            + " and must not start with a digit, got '" + tablePrefix + "'");
        }
    }

    /** Tables with the default prefix and no schema qualifier. */
    public static JdbcStoreTables defaults() {
        return new JdbcStoreTables(null, DEFAULT_PREFIX);
    }

    /** Tables as configured by {@link JdbcStoreProperties}. */
    public static JdbcStoreTables of(JdbcStoreProperties properties) {
        return new JdbcStoreTables(properties.getSchema(), properties.getTablePrefix());
    }

    /** Qualified name of the idempotency table. */
    public String idempotency() {
        return qualified("idempotency");
    }

    /** Qualified name of the dead-letter table. */
    public String deadLetter() {
        return qualified("dead_letter");
    }

    /** Qualified name of the delivery-event table. */
    public String deliveryEvent() {
        return qualified("delivery_event");
    }

    /**
     * Unqualified name for a constraint or index. PostgreSQL creates
     * indexes in the schema of their table, so these never carry the
     * schema.
     */
    public String objectName(String suffix) {
        return tablePrefix + suffix;
    }

    private String qualified(String table) {
        String name = tablePrefix + table;
        return schema == null ? name : schema + "." + name;
    }
}
