package com.lazydevs.notification.store.jdbc;

import java.util.List;
import java.util.Objects;

/**
 * Renders the reference PostgreSQL DDL for the JDBC stores, including the
 * template store's version and event tables (DD-38, since 1.3.0).
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
                fingerprint     VARCHAR(64),
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
            "CREATE INDEX IF NOT EXISTS {p}delivery_event_expires_at_idx ON {delivery_event} (expires_at)",
            """
            CREATE TABLE IF NOT EXISTS {audit} (
                id                  BIGINT       GENERATED ALWAYS AS IDENTITY,
                tenant_id           VARCHAR(255),
                tenant_key          VARCHAR(255) GENERATED ALWAYS AS (COALESCE(tenant_id, '')) STORED,
                request_id          VARCHAR(255) NOT NULL,
                correlation_id      VARCHAR(255),
                caller_id           VARCHAR(255),
                caller_key          VARCHAR(255) GENERATED ALWAYS AS (COALESCE(caller_id, '')) STORED,
                idem_key            VARCHAR(255),
                replay_of           VARCHAR(255),
                notification_type   VARCHAR(255),
                channel             VARCHAR(32),
                provider            VARCHAR(128),
                template_id         VARCHAR(255),
                recipient_summary   TEXT,
                recipient_count     INTEGER,
                status              VARCHAR(32)  NOT NULL,
                failure_type        VARCHAR(32),
                provider_message_id VARCHAR(512),
                error_code          VARCHAR(255),
                error_message       TEXT,
                metadata            TEXT,
                received_at         TIMESTAMPTZ  NOT NULL,
                processed_at        TIMESTAMPTZ,
                sent_at             TIMESTAMPTZ,
                delivered_at        TIMESTAMPTZ,
                failed_at           TIMESTAMPTZ,
                updated_at          TIMESTAMPTZ  NOT NULL,
                created_at          TIMESTAMPTZ  NOT NULL,
                expires_at          TIMESTAMPTZ  NOT NULL,
                CONSTRAINT {p}audit_pk PRIMARY KEY (id),
                CONSTRAINT {p}audit_request_uq UNIQUE (tenant_key, request_id)
            )""",
            "CREATE INDEX IF NOT EXISTS {p}audit_idem_key_idx"
                    + " ON {audit} (tenant_key, caller_key, idem_key, received_at) WHERE idem_key IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS {p}audit_tenant_received_idx ON {audit} (tenant_key, received_at)",
            "CREATE INDEX IF NOT EXISTS {p}audit_expires_at_idx ON {audit} (expires_at)",
            """
            CREATE TABLE IF NOT EXISTS {push_device} (
                id              BIGINT        GENERATED ALWAYS AS IDENTITY,
                tenant_id       VARCHAR(255),
                tenant_key      VARCHAR(255)  GENERATED ALWAYS AS (COALESCE(tenant_id, '')) STORED,
                user_id         VARCHAR(255)  NOT NULL,
                installation_id VARCHAR(255)  NOT NULL,
                app_id          VARCHAR(255),
                platform        VARCHAR(16)   NOT NULL,
                token           VARCHAR(4096) NOT NULL,
                token_hash      VARCHAR(32)   NOT NULL,
                token_sha256    CHAR(64)      NOT NULL,
                locale          VARCHAR(35),
                timezone        VARCHAR(64),
                attributes      TEXT          NOT NULL,
                created_at      TIMESTAMPTZ   NOT NULL,
                last_seen_at    TIMESTAMPTZ   NOT NULL,
                expires_at      TIMESTAMPTZ   NOT NULL,
                CONSTRAINT {p}push_device_pk PRIMARY KEY (id),
                CONSTRAINT {p}push_device_installation_uq UNIQUE (tenant_key, user_id, installation_id),
                CONSTRAINT {p}push_device_token_uq UNIQUE (tenant_key, token_sha256)
            )""",
            "CREATE INDEX IF NOT EXISTS {p}push_device_user_seen_idx"
                    + " ON {push_device} (tenant_key, user_id, last_seen_at)",
            "CREATE INDEX IF NOT EXISTS {p}push_device_token_hash_idx ON {push_device} (tenant_key, token_hash)",
            "CREATE INDEX IF NOT EXISTS {p}push_device_expires_at_idx ON {push_device} (expires_at)",
            """
            CREATE TABLE IF NOT EXISTS {template_version} (
                id              BIGINT       GENERATED ALWAYS AS IDENTITY,
                tenant_id       VARCHAR(255),
                tenant_key      VARCHAR(255) GENERATED ALWAYS AS (COALESCE(tenant_id, '')) STORED,
                channel         VARCHAR(32)  NOT NULL,
                template_id     VARCHAR(128) NOT NULL,
                locale          VARCHAR(128) NOT NULL DEFAULT '',
                version         INTEGER      NOT NULL,
                status          VARCHAR(16)  NOT NULL,
                content_type    VARCHAR(16)  NOT NULL,
                content         TEXT         NOT NULL,
                sample_model    TEXT,
                revision        BIGINT       NOT NULL,
                created_by      VARCHAR(255) NOT NULL,
                created_at      TIMESTAMPTZ  NOT NULL,
                updated_by      VARCHAR(255) NOT NULL,
                updated_at      TIMESTAMPTZ  NOT NULL,
                published_by    VARCHAR(255),
                published_at    TIMESTAMPTZ,
                note            TEXT,
                provider_status VARCHAR(16),
                provider_ref    VARCHAR(512),
                provider_reason TEXT,
                CONSTRAINT {p}template_version_pk PRIMARY KEY (id),
                CONSTRAINT {p}template_version_uq UNIQUE (tenant_key, channel, template_id, locale, version),
                CONSTRAINT {p}template_version_status_ck
                    CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED', 'ARCHIVED'))
            )""",
            "CREATE UNIQUE INDEX IF NOT EXISTS {p}template_version_published_uq"
                    + " ON {template_version} (tenant_key, channel, template_id, locale) WHERE status = 'PUBLISHED'",
            "CREATE UNIQUE INDEX IF NOT EXISTS {p}template_version_draft_uq"
                    + " ON {template_version} (tenant_key, channel, template_id, locale) WHERE status = 'DRAFT'",
            """
            CREATE TABLE IF NOT EXISTS {template_event} (
                id          BIGINT       GENERATED ALWAYS AS IDENTITY,
                tenant_id   VARCHAR(255),
                tenant_key  VARCHAR(255) GENERATED ALWAYS AS (COALESCE(tenant_id, '')) STORED,
                channel     VARCHAR(32)  NOT NULL,
                template_id VARCHAR(128) NOT NULL,
                locale      VARCHAR(128) NOT NULL DEFAULT '',
                action      VARCHAR(32)  NOT NULL,
                version     INTEGER,
                actor       VARCHAR(255) NOT NULL,
                occurred_at TIMESTAMPTZ  NOT NULL,
                note        TEXT,
                CONSTRAINT {p}template_event_pk PRIMARY KEY (id)
            )""",
            "CREATE INDEX IF NOT EXISTS {p}template_event_key_idx"
                    + " ON {template_event} (tenant_key, channel, template_id, locale, id)");

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
                .replace("{audit}", tables.audit())
                .replace("{push_device}", tables.pushDevice())
                .replace("{template_version}", tables.templateVersion())
                .replace("{template_event}", tables.templateEvent())
                .replace("{p}", tables.objectName(""));
    }
}
