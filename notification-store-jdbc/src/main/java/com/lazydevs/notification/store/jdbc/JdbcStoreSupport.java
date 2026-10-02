package com.lazydevs.notification.store.jdbc;

import lazydevs.persistence.connection.multitenant.TenantContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Small helpers shared by the JDBC stores.
 */
final class JdbcStoreSupport {

    /**
     * SQL fragment for {@code now() + <millis>} with the milliseconds bound
     * to the named parameter {@code param}. Database time is used
     * everywhere so replicas with skewed clocks agree on expiry and leases.
     */
    static String nowPlusMillis(String param) {
        return "now() + (:" + param + " * INTERVAL '1 millisecond')";
    }

    private JdbcStoreSupport() {
    }

    /**
     * Tenant to stamp on a row: the record's own tenant when it carries
     * one (the SPI lookup key), else the current {@link TenantContext}
     * tenant, else {@code null}. Same precedence as
     * {@code DefaultNotificationService}: request first, context second.
     */
    static String tenantFor(String explicitTenant) {
        if (hasText(explicitTenant)) {
            return explicitTenant;
        }
        return currentTenant();
    }

    /** The current {@link TenantContext} tenant, or {@code null} when none is set. */
    static String currentTenant() {
        String tenant = TenantContext.getTenantId();
        return hasText(tenant) ? tenant : null;
    }

    static long millis(Duration duration) {
        return duration.toMillis();
    }

    static Duration requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive duration, got " + duration);
        }
        return duration;
    }

    static int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1, got " + value);
        }
        return value;
    }

    /** {@code Instant} as a JDBC-friendly {@code OffsetDateTime} in UTC. */
    static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * {@code Enum.valueOf} that maps a {@code null} or unknown name to
     * {@code fallback}, so a row written by a newer version with a constant
     * this version lacks still reads.
     */
    static <E extends Enum<E>> E enumOrDefault(Class<E> type, String name, E fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
