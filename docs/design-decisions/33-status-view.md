# Decision 33: Status View over the Audit Record

## Status: DECIDED

## Context

`NotificationService` had no read API.
A caller that sent with an idempotency key and then lost the response (a timeout, a crash) could only send again and hope for a replay; an operator could only use `GET /admin/audit/{requestId}`, and only with a custom audit backend, because the library shipped no audit persistence: the default `NoOpAuditService` keeps nothing and `notification.audit.enabled=true` without an application bean failed startup.

The audit path was the natural place to build on.
It is called synchronously from the send path at receipt and at completion, it is keyed by request id, DD-07 already keeps a masked recipient summary instead of addresses, and DD-18 already joins it with delivery events.
Nothing in it is fire-and-forget or lossy by design (the `async` flag was never implemented), so it can back a status view.

## Decision

### Read API

`NotificationService` gains two default methods, returning `Optional.empty()` so other implementations keep compiling:

- `find(String tenantId, String requestId)`
- `findByIdempotencyKey(String tenantId, String callerId, String idempotencyKey)`, the most recently received request that used the key.

A null or blank tenant means the default tenant, as for a send.
`DefaultNotificationService` reads the audit record and joins the latest delivery event for its provider and provider message id from the `DeliveryEventStore`, when one is configured.

### NotificationView

`NotificationView` (notification-api, record, `@JsonInclude(NON_NULL)`) carries: request id, tenant, caller, idempotency key, correlation id, channel, notification type, provider, template id, recipient count, the request's own metadata map, status, failure type, error code, scrubbed error message, provider message id, received, sent, delivered, failed and updated timestamps, and the latest delivery status (as text) with its timestamp.
`deliveredAt` comes from the audit record or, failing that, from a `DELIVERED` delivery event.
It never carries recipient addresses.

Lifecycle: `PROCESSING` while the send is under way, then `SENT`, then a delivery status from a provider callback; or `FAILED` / `REJECTED` with failure type, error code and `failedAt`; or `FAILED` with `IN_PROGRESS_TIMEOUT` / `AMBIGUOUS` after the idempotency timeout settled it (DD-32).
An idempotent replay creates no record, so the view of a key stays the view of the request whose outcome is replayed.

### Audit record and store

`NotificationAudit` gains `idempotencyKey`, `recipientCount`, `failureType`, `failedAt`, `updatedAt` and `applyOutcome(response, now)`, the single rule for applying an outcome; the 1.2.0 all-arguments constructor is kept, deprecated.

`NotificationAuditStore` (notification-api) is the storage SPI: `insert`, `recordOutcome`, `find`, `findByIdempotencyKey`, `findRecent`.
`NotificationAuditService` gains `recordOutcome(NotificationResponse)` (default: the old `updateStatus`), `findByRequestId(tenantId, requestId)` and `findByIdempotencyKey(...)` as default methods; the service now reports outcomes through `recordOutcome`, so failure type and timestamps reach the record, with the error message already scrubbed (DD-32).

With `notification.audit.enabled=true` (still `false` by default) the core registers `StoreBackedAuditService` over the `NotificationAuditStore` of the store family:

- `memory`: `InMemoryNotificationAuditStore`, bounded by `notification.audit.max-entries` (default 10000) and expired after `notification.audit.retention-days` (default 90).
- `jdbc`: `JdbcNotificationAuditStore`, table `<prefix>audit`.
- `redis`: no audit store; startup fails with a message naming the options, as 1.2.0 failed without a bean; `notification.redis.audit.enabled=false` keeps audit in memory.

A new `StoreFeature.AUDIT` gives audit the usual per-feature resolution and the missing-module check.
An application `NotificationAuditStore` or `NotificationAuditService` bean wins.

`StoreBackedAuditService` logs and swallows store errors on writes, so a send never fails because of its audit record (DD-07); reads propagate.

### JDBC table

One row per `(tenant_key, request_id)` (unique; a repeated request id replaces the row), with `tenant_id` nullable and the generated `tenant_key` / `caller_key` columns of the other tables.
Indexes: the unique constraint for `(tenant, request id)`, a partial index on `(tenant_key, caller_key, idem_key, received_at)` for key lookups, `(tenant_key, received_at)` for the recent listing, and `expires_at` for the purge.
`status` is `VARCHAR(32)` and `failure_type` `VARCHAR(32)`, without check constraints, so later statuses need no migration; an unknown value reads as `null` (`UNKNOWN` for failure type).
Metadata is JSON in a `TEXT` column.
Rows expire `notification.audit.retention-days` after creation and are purged by `JdbcStorePurger` with the other tables.
The DDL is additive: a new table and three indexes in `JdbcStoreSchema` and the reference SQL.

## Reasoning

### Why the audit record and not a new status store

The audit record already has the right key, the right lifecycle hooks and the PII rule.
A second store would duplicate the writes and could disagree with the audit trail operators already use.

### Why a store SPI in notification-api

`NotificationAuditService` lives in notification-core, which the JDBC module only uses in tests; every other JDBC store implements an SPI from notification-api.
Splitting "build the record" (core) from "keep the record" (store) keeps that layering and lets the in-memory and JDBC stores share one record-building path.

### Why the delivery status is text

`DeliveryStatus` will gain values (for example read receipts); the view exposes the name so a new status needs neither a schema change nor a view change.

### Why no Redis audit store

A Redis audit store would need secondary indexes for the key lookup and the recent listing; the JDBC store gives both for free.
Failing startup for Redis keeps the 1.2.0 behaviour and avoids a per-instance audit silently standing in for a shared one.

## Consequences

### Positive

- Callers can ask what happened to a send by request id or idempotency key.
- `GET /admin/audit/*` and the DD-18 join work out of the box once audit is enabled.
- The view never exposes recipient addresses.

### Negative

- Enabling audit adds one insert and one update per send.
- The in-memory store is per instance and scans for key lookups; multi-instance deployments need the JDBC store.
- The audit write is best effort: during a database outage the send proceeds and its view is missing or stale.
- `store-request-payload`, `store-response-payload` and `async` remain unimplemented.

## Related Decisions

- [07-audit-persistence.md](./07-audit-persistence.md) - audit record and masking
- [17-delivery-event-store.md](./17-delivery-event-store.md) - delivery events
- [18-audit-delivery-join.md](./18-audit-delivery-join.md) - the join the view reuses
- [20-admin-audit-browse.md](./20-admin-audit-browse.md) - admin endpoints over the same service
- [32-idempotency-fingerprint-failure-policy.md](./32-idempotency-fingerprint-failure-policy.md) - scrubbed messages and the timeout settle
