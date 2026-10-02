# notification-store-jdbc

PostgreSQL-backed implementations of three notification-service SPIs, written as plain SQL over Spring's `JdbcClient`:

| SPI | Implementation |
|---|---|
| `IdempotencyStore` | `JdbcIdempotencyStore` |
| `DeadLetterStore` | `JdbcDeadLetterStore` |
| `DeliveryEventStore` (also a `DeliveryEventListener`) | `JdbcDeliveryEventStore` |

There is no ORM, no JPA or Hibernate, and no migration tool.
The library never executes DDL and never needs a superuser, a table owner, or `BYPASSRLS`: every statement is plain DML on the configured tables.

Rate limiting is out of scope for this module.
`RateLimiter` stays on the in-memory Bucket4j limiter or on `RedisRateLimiter` from `notification-redis`.

## Usage

Add the module next to the starter.
The host application supplies the `DataSource` and the JDBC driver; the module does not depend on a driver.

```xml
<dependency>
  <groupId>com.github.ifrugal</groupId>
  <artifactId>notification-store-jdbc</artifactId>
</dependency>
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
</dependency>
```

Select the JDBC stores and turn on the features you use:

```yaml
notification:
  store:
    type: jdbc
  idempotency:
    enabled: true          # default
    ttl: PT24H             # default; drives expires_at of idempotency rows
  dead-letter:
    enabled: true
    max-entries: 1000      # default; bound of DeadLetterStore.snapshot()
  delivery-events:
    enabled: true
    max-entries: 5000      # default; bound of snapshot() and findByProviderMessageId()
```

`JdbcStoreAutoConfiguration` activates only with `notification.store.type=jdbc` and `JdbcClient` on the classpath.
Each store also requires its usual feature flag (`notification.idempotency.enabled`, which defaults to on; `notification.dead-letter.enabled`; `notification.delivery-events.enabled`) and backs off when the application defines its own bean of that SPI.
The auto-configuration runs before the core in-memory defaults, so those back off when a JDBC store is registered.

## Properties

| Property | Default | Meaning |
|---|---|---|
| `notification.store.jdbc.schema` | none | Schema that holds the tables. Unset uses the connection's search path. |
| `notification.store.jdbc.table-prefix` | `notification_` | Prefix for every table, constraint and index name. |
| `notification.store.jdbc.datasource-bean-name` | none | DataSource bean to use. Unset picks the single, or `@Primary`, DataSource. |
| `notification.store.jdbc.dialect` | `POSTGRESQL` | SQL dialect. Only PostgreSQL is implemented. |
| `notification.store.jdbc.dead-letter-retention` | `P30D` | Lifetime of a dead-letter row. |
| `notification.store.jdbc.delivery-event-retention` | `P30D` | Lifetime of a delivery-event row. |
| `notification.store.jdbc.purge.enabled` | `false` | Run the module's own scheduled purge of expired rows. |
| `notification.store.jdbc.purge.interval` | `PT1H` | Delay between purge runs. |
| `notification.store.jdbc.purge.batch-size` | `1000` | Maximum rows deleted per purge statement. |

The schema and prefix are interpolated into SQL, so they must be plain identifiers (`[A-Za-z_][A-Za-z0-9_]*`); anything else fails startup.
If the application has several DataSources and none is `@Primary`, startup fails and names `datasource-bean-name`.

## Schema and migrations

The host runs the migrations.
The reference DDL ships in the jar as `db/postgresql/notification-store.sql` (default prefix, no schema qualifier) and is reproduced below.
It needs PostgreSQL 12 or later for the stored generated `tenant_key` columns.
Copy it into your Flyway, Liquibase or other migration, or render it for your own prefix and schema:

```java
String ddl = JdbcStoreSchema.postgresql(new JdbcStoreTables("notify", "ns_")).ddl();
```

```sql
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
```

The tables are:

- `idempotency`: one row per `(tenant, caller, idempotency key)`, with the cached `NotificationResponse` as JSON.
- `dead_letter`: one row per `(tenant, request id)`, with the request and the response as JSON and a `claimed_until` lease column.
- `delivery_event`: one row per webhook event, de-duplicated on `(tenant, provider, provider event id)`, with the provider attributes as JSON.

Fields that are queried (tenant, request id, idempotency key, provider name, provider message id, status, attempts, timestamps, lease) are real, indexed columns.
Payloads are JSON in `TEXT` columns.

### Storage format

The stores always serialise with their own module-owned Jackson `ObjectMapper`, never the application's bean.
It registers `JavaTimeModule`, writes dates as ISO-8601 strings, and ignores unknown properties on read.
Since 1.1.2 an unknown `FailureType` or `DeliveryStatus` constant, in a JSON payload or in the `failure_type` and `status` columns, reads as `UNKNOWN`.
The idempotency table stores a `FAILED` or `REJECTED` response without its `errorMessage`, because such a response is never replayed.
This keeps the stored JSON stable when the host changes its own Jackson configuration, and lets rows written by a newer library version still be read.

## Grants and row-level security

The application role needs only these grants:

```sql
GRANT SELECT, INSERT, UPDATE, DELETE
  ON notification_idempotency, notification_dead_letter, notification_delivery_event
  TO notification_app;
```

With a custom `notification.store.jdbc.schema`, also grant `USAGE` on that schema.
Identity columns need no separate sequence grant.

Every table has a nullable `tenant_id` column, so a tenant-isolation policy is straightforward.
For example, with the tenant applied per connection or transaction through `SET app.tenant = '...'`:

```sql
ALTER TABLE notification_dead_letter ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_dead_letter
  USING (tenant_id = current_setting('app.tenant', true))
  WITH CHECK (tenant_id = current_setting('app.tenant', true));
```

`JdbcStoreRlsIT` runs every store as a non-owner role without `BYPASSRLS` under exactly this policy, on all three tables.
Each tenant sees and changes only its own rows, and a write for another tenant is refused by the policy.

How `tenant_id` is filled:

- Idempotency rows use `IdempotencyKey.tenantId()`.
- Dead-letter rows use the request's tenant when it has one, else the current `TenantContext` tenant, else `NULL`.
- Delivery-event rows use the current `TenantContext` tenant, else `NULL`, because delivery events carry no tenant of their own.

Under row-level security the session tenant must match the tenant written, otherwise the insert is refused.
The dead-letter and delivery-event stores log and swallow that failure, as their SPIs require; the idempotency store lets it propagate.

## Dead-letter replay across replicas

`JdbcDeadLetterStore` implements `DeadLetterStore.claim` and `release` so several replicas can drain the same tenant without replaying an entry twice:

1. `claim(tenantId, limit, lease)` leases up to `limit` unclaimed entries, oldest first, in one `UPDATE ... RETURNING` statement over a `FOR UPDATE SKIP LOCKED` selection; `claim(tenantId, requestId, lease)` leases that one entry the same way, for single-entry replay.
2. After a successful replay, `remove(tenantId, requestId)` deletes the entry; removal is the acknowledgement.
3. After a failed replay, `release(tenantId, requestId)` clears the lease so the entry is claimable again at once.
4. An entry that is neither removed nor released, for example because the replica crashed, becomes claimable when its lease ends.

All expiry and lease arithmetic uses database time (`now()`), so replicas with skewed clocks still agree.
The REST replay endpoints follow this cycle, with the lease from `notification.dead-letter.replay-lease` (default `PT5M`).

## Expiry and purge

Each row has an `expires_at`, from `notification.idempotency.ttl` for idempotency rows and from the two retention properties for the other tables.
Expired rows are invisible to every read immediately; purging only reclaims the space.

- `purgeExpired(batchSize)` on each store deletes at most one batch of expired rows and returns the count.
- `JdbcStorePurger.purgeAll()` drains every registered store batch by batch.
- `IdempotencyStore.evictExpired()` on the JDBC store does the same for idempotency rows.
- With `notification.store.jdbc.purge.enabled=true`, a single daemon thread owned by the module calls `purgeAll()` every `purge.interval`; `@EnableScheduling` is not needed.

Purges on several replicas split the work through `SKIP LOCKED` instead of blocking each other.
Under row-level security a purge only reaches the rows its session can see, so run it with a tenant setting, or a role, that covers the rows to purge.

## Reads and bounds

- `DeadLetterStore.snapshot()` returns at most `notification.dead-letter.max-entries` rows, most recent first; it never loads the whole table.
- `DeadLetterStore.size()` is a global `COUNT`, not narrowed by `TenantContext`, matching the in-memory and Redis stores (row-level security still applies).
- `DeliveryEventStore.snapshot()` and `findByProviderMessageId()` return at most `notification.delivery-events.max-entries` rows, most recent first.
- `DeliveryEventStore.size()` is a `COUNT` across tenants (row-level security still applies).
- The `dlq` and `deliveryEvents` health indicators call `size()`, so every health check runs a `COUNT(*)` over the table.
  On a large table, switch them off with `management.health.dlq.enabled=false` and `management.health.delivery-events.enabled=false` (since 1.1.2), and watch DLQ growth through the DD-22 metrics instead.

## GraalVM native image

The stores use no reflection apart from Jackson on the model types they serialise.
`JdbcStoreRuntimeHints`, imported by the auto-configuration, registers binding hints for `NotificationRequest` (with its attachments and recipient subtypes) and `NotificationResponse`.

## Testing

`./mvnw -pl notification-store-jdbc -am verify` runs the unit tests and, when Docker is available, the `*IT` integration tests against `postgres:16-alpine` through Testcontainers.
Without Docker the integration tests are skipped.
