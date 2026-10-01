package com.lazydevs.notification.store.jdbc;

import java.util.List;
import java.util.Objects;

/**
 * Renders the reference PostgreSQL DDL for the JDBC stores.
 *
 * <p>The library never executes DDL. This helper exists so hosts can
 * generate the script for their own prefix and schema and feed it to
 * their migration tool, and so tests can create the tables. The shipped
 * resource {@code db/postgresql/notification-store.sql} is exactly
 * {@code JdbcStoreSchema.postgresql(JdbcStoreTables.defaults()).ddl()}.
 *
 * <p>Requires PostgreSQL 12 or later (stored generated columns). The
 * {@code tenant_key} columns map a {@code NULL} tenant to {@code ''} so
 * the uniqueness constraints treat "no tenant" as one scope instead of
 * letting {@code NULL}s never collide.
 */
public final class JdbcStoreSchema {

    private static final String HEADER = """
            -- notification-store-jdbc reference schema (PostgreSQL 12+).
            -- The library never runs DDL: apply this with your own migration tool.
            -- Regenerate for another prefix or schema with
            -- JdbcStoreSchema.postgresql(new JdbcStoreTables(schema, prefix)).ddl().
            """;

    private static final List<String> TEMPLATE = List.of(
            """
            CREATE TABLE IF NOT EXISTS {idempotency} (
                tenant_id       VARCHAR(255),
                tenant_key      VARCHAR(255) GENERATED ALWAYS AS (COALESCE(tenant_id, '')) STORED,
                caller_id       VARCHAR(255),
                caller_key      VARCHAR(255) GENERATED ALWAYS AS (COALESCE(caller_id, '')) STORED,
                idem_key        VARCHAR(255) NOT NULL,
                notification_id VARCHAR(255) NOT NULL,
                status          VARCHAR(16)  NOT NULL,
                response        TEXT,
                created_at      TIMESTAMPTZ  NOT NULL,
                recorded_at     TIMESTAMPTZ  NOT NULL,
                expires_at      TIMESTAMPTZ  NOT NULL,
                CONSTRAINT {p}idempotency_pk PRIMARY KEY (tenant_key, caller_key, idem_key)
            )""",
            "CREATE INDEX IF NOT EXISTS {p}idempotency_expires_at_idx ON {idempotency} (expires_at)",
            """
            CREATE TABLE IF NOT EXISTS {dead_letter} (
                id            BIGINT       GENERATED ALWAYS AS IDENTITY,
                tenant_id     VARCHAR(255),
                tenant_key    VARCHAR(255) GENERATED ALWAYS AS (COALESCE(tenant_id, '')) STORED,
                request_id    VARCHAR(255) NOT NULL,
                provider_name VARCHAR(128),
                status        VARCHAR(16)  NOT NULL,
                failure_type  VARCHAR(16)  NOT NULL,
                attempts      INTEGER      NOT NULL,
                failed_at     TIMESTAMPTZ  NOT NULL,
                request       TEXT         NOT NULL,
                response      TEXT         NOT NULL,
                claimed_until TIMESTAMPTZ,
                created_at    TIMESTAMPTZ  NOT NULL,
                expires_at    TIMESTAMPTZ  NOT NULL,
                CONSTRAINT {p}dead_letter_pk PRIMARY KEY (id),
                CONSTRAINT {p}dead_letter_request_uq UNIQUE (tenant_key, request_id)
            )""",
            "CREATE INDEX IF NOT EXISTS {p}dead_letter_tenant_created_idx ON {dead_letter} (tenant_key, created_at)",
            "CREATE INDEX IF NOT EXISTS {p}dead_letter_created_at_idx ON {dead_letter} (created_at)",
            "CREATE INDEX IF NOT EXISTS {p}dead_letter_expires_at_idx ON {dead_letter} (expires_at)",
            """
            CREATE TABLE IF NOT EXISTS {delivery_event} (
                id                  BIGINT       GENERATED ALWAYS AS IDENTITY,
                tenant_id           VARCHAR(255),
                tenant_key          VARCHAR(255) GENERATED ALWAYS AS (COALESCE(tenant_id, '')) STORED,
                provider_name       VARCHAR(128) NOT NULL,
                provider_message_id VARCHAR(512) NOT NULL,
                provider_event_id   VARCHAR(512),
                status              VARCHAR(32)  NOT NULL,
                reason              TEXT,
                event_timestamp     TIMESTAMPTZ  NOT NULL,
                attributes          TEXT         NOT NULL,
                created_at          TIMESTAMPTZ  NOT NULL,
                expires_at          TIMESTAMPTZ  NOT NULL,
                CONSTRAINT {p}delivery_event_pk PRIMARY KEY (id),
                CONSTRAINT {p}delivery_event_event_uq UNIQUE (tenant_key, provider_name, provider_event_id)
            )""",
            "CREATE INDEX IF NOT EXISTS {p}delivery_event_message_idx"
                    + " ON {delivery_event} (provider_name, provider_message_id)",
            "CREATE INDEX IF NOT EXISTS {p}delivery_event_created_at_idx ON {delivery_event} (created_at)",
            "CREATE INDEX IF NOT EXISTS {p}delivery_event_expires_at_idx ON {delivery_event} (expires_at)");

    private final JdbcStoreTables tables;

    private JdbcStoreSchema(JdbcStoreTables tables) {
        this.tables = Objects.requireNonNull(tables, "tables");
    }

    /** PostgreSQL DDL for the given table names. */
    public static JdbcStoreSchema postgresql(JdbcStoreTables tables) {
        return new JdbcStoreSchema(tables);
    }

    /** PostgreSQL DDL for the given prefix and (nullable) schema. */
    public static JdbcStoreSchema postgresql(String tablePrefix, String schema) {
        return new JdbcStoreSchema(new JdbcStoreTables(schema, tablePrefix));
    }

    /** The individual statements, without trailing semicolons, in execution order. */
    public List<String> statements() {
        return TEMPLATE.stream().map(this::render).toList();
    }

    /** The full script: a comment header followed by every statement terminated with {@code ;}. */
    public String ddl() {
        return HEADER + "\n" + String.join(";\n\n", statements()) + ";\n";
    }

    private String render(String template) {
        return template
                .replace("{idempotency}", tables.idempotency())
                .replace("{dead_letter}", tables.deadLetter())
                .replace("{delivery_event}", tables.deliveryEvent())
                .replace("{p}", tables.objectName(""));
    }
}
