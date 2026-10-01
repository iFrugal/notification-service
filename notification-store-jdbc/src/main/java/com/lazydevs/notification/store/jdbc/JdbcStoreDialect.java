package com.lazydevs.notification.store.jdbc;

/**
 * SQL dialects the JDBC stores can speak. Only PostgreSQL (12 or later,
 * for stored generated columns) is implemented; other values will be
 * added together with their SQL.
 */
public enum JdbcStoreDialect {
    POSTGRESQL
}
