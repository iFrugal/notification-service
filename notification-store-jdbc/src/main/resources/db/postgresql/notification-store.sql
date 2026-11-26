-- notification-store-jdbc reference schema (PostgreSQL 12+).
-- The library never runs DDL: apply this with your own migration tool.
-- Regenerate for another prefix or schema with
-- JdbcStoreSchema.postgresql(new JdbcStoreTables(schema, prefix)).ddl().

CREATE TABLE IF NOT EXISTS notification_idempotency (
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
    CONSTRAINT notification_idempotency_pk PRIMARY KEY (tenant_key, caller_key, idem_key)
);

CREATE INDEX IF NOT EXISTS notification_idempotency_expires_at_idx ON notification_idempotency (expires_at);

CREATE TABLE IF NOT EXISTS notification_dead_letter (
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
    CONSTRAINT notification_dead_letter_pk PRIMARY KEY (id),
    CONSTRAINT notification_dead_letter_request_uq UNIQUE (tenant_key, request_id)
);

CREATE INDEX IF NOT EXISTS notification_dead_letter_tenant_created_idx ON notification_dead_letter (tenant_key, created_at);

CREATE INDEX IF NOT EXISTS notification_dead_letter_created_at_idx ON notification_dead_letter (created_at);

CREATE INDEX IF NOT EXISTS notification_dead_letter_expires_at_idx ON notification_dead_letter (expires_at);

CREATE TABLE IF NOT EXISTS notification_delivery_event (
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
    CONSTRAINT notification_delivery_event_pk PRIMARY KEY (id),
    CONSTRAINT notification_delivery_event_event_uq UNIQUE (tenant_key, provider_name, provider_event_id)
);

CREATE INDEX IF NOT EXISTS notification_delivery_event_message_idx ON notification_delivery_event (provider_name, provider_message_id);

CREATE INDEX IF NOT EXISTS notification_delivery_event_created_at_idx ON notification_delivery_event (created_at);

CREATE INDEX IF NOT EXISTS notification_delivery_event_expires_at_idx ON notification_delivery_event (expires_at);

CREATE TABLE IF NOT EXISTS notification_audit (
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
    CONSTRAINT notification_audit_pk PRIMARY KEY (id),
    CONSTRAINT notification_audit_request_uq UNIQUE (tenant_key, request_id)
);

CREATE INDEX IF NOT EXISTS notification_audit_idem_key_idx ON notification_audit (tenant_key, caller_key, idem_key, received_at) WHERE idem_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS notification_audit_tenant_received_idx ON notification_audit (tenant_key, received_at);

CREATE INDEX IF NOT EXISTS notification_audit_expires_at_idx ON notification_audit (expires_at);
