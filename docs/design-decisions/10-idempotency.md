# Decision 10: Idempotency Key (First-Class)

## Status: DECIDED

## Context

The existing `NotificationRequest` carries a `correlationId` for cross-system
tracing, but nothing guarantees that a caller retrying a transiently-failed
request will not trigger a second provider call. Concretely:

1. Client `POST /api/v1/notifications` — server receives, provider call
   starts, connection times out.
2. Client retries — server has no way to know this is the same logical
   send, so it dispatches a second provider call.
3. Recipient gets two emails / SMS / push notifications.

Email and push over-delivery is annoying; SMS and WhatsApp over-delivery is
expensive and can trigger spam classifiers. Internal-platform deployments in
particular care about this because they batch-process events (Kafka replay,
DLQ replay, at-least-once consumers) where retries are the norm.

We need a first-class idempotency mechanism. This DD specifies the contract,
the storage SPI, the default in-memory implementation, and the integration
into the REST and programmatic paths.

## Decision

Introduce an **optional idempotency key** on `NotificationRequest`. If the
caller provides one, duplicate requests within a configurable TTL deduplicate
against a pluggable `IdempotencyStore`.

### Request field

```java
// In NotificationRequest
@Size(max = 255, message = "idempotencyKey must be at most 255 characters")
private String idempotencyKey;
```

- Optional. If absent, the request is processed exactly as today — no
  idempotency semantics.
- Max 255 characters. Long enough for UUIDs, request ids, hash digests;
  short enough to keep storage-key indexing efficient.
- Opaque to the service. The caller is responsible for generating a value
  that uniquely identifies the logical send intent.

### Key scope

The key is **not** global. Duplicates are detected per-tenant and
per-caller:

```
effectiveKey = (tenantId, callerId, idempotencyKey)
```

- `tenantId` comes from the existing `X-Tenant-Id` header or request field
  (see DD-03).
- `callerId` comes from the `X-Service-Id` header (see DD-11, which this DD
  does not block on — if `callerId` is absent, it is treated as `null` and
  two requests from different callers with the same `idempotencyKey` *will*
  be treated as duplicates until DD-11 lands).
- `idempotencyKey` is the request field above.

Rationale for this scope in §Reasoning.

### SPI — `IdempotencyStore`

Defined in `notification-api`:

```java
package com.lazydevs.notification.api.idempotency;

public interface IdempotencyStore {

    /**
     * Look up an existing record for this key.
     *
     * @return the record if one exists and is still within TTL; empty otherwise.
     */
    Optional<IdempotencyRecord> findExisting(IdempotencyKey key);

    /**
     * Atomically register this key as IN_PROGRESS.
     *
     * Implementations MUST ensure that if two calls for the same key race,
     * exactly one returns {@code true}. The loser sees {@code false} and
     * can re-read via {@link #findExisting} to obtain the in-progress
     * notification id.
     *
     * @return {@code true} if this caller won the race; {@code false} if
     *         a record for this key already exists (IN_PROGRESS or COMPLETE).
     */
    boolean markInProgress(IdempotencyKey key, String notificationId);

    /**
     * Record the terminal response against this key. The key then counts as
     * COMPLETE until TTL expiry.
     */
    void markComplete(IdempotencyKey key, NotificationResponse response);

    /**
     * Remove expired entries. Called periodically by a scheduled bean;
     * implementations MAY also prune opportunistically on read.
     */
    void evictExpired();
}
```

Supporting types in `notification-api`:

```java
public record IdempotencyKey(
        String tenantId,
        String callerId,
        String idempotencyKey) {
    public IdempotencyKey {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        // callerId intentionally nullable until DD-11 wires X-Service-Id.
    }
}

public record IdempotencyRecord(
        String notificationId,
        IdempotencyStatus status,
        NotificationResponse response,   // null when status == IN_PROGRESS
        Instant recordedAt) { }

public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETE
}
```

### Default implementation — `CaffeineIdempotencyStore`

Lives in `notification-core`. Backed by a Caffeine
`Cache<IdempotencyKey, IdempotencyRecord>` with:

- `expireAfterWrite` = `notification.idempotency.ttl` (default `PT24H`).
- `maximumSize` = `notification.idempotency.max-entries` (default `100_000`).
- `Scheduler.systemScheduler()` so time-based expiry runs without explicit
  `cleanUp()`.

`markInProgress` uses `Cache.asMap().putIfAbsent(...)` to get atomicity
without a separate lock. `markComplete` replaces the record unconditionally
(the same thread holds the in-progress entry).

This default is deliberate:

- **Zero operational cost** — no database, no Redis, no file I/O.
- **Library-mode friendly** — a Spring Boot consumer embedding the starter
  doesn't pay for an extra moving part.
- **Service-mode gap accepted** — in a multi-replica service deployment, the
  cache is per-JVM, so two replicas don't share idempotency state. That's
  called out clearly in §Consequences and addressed by the pluggable SPI.

### Future implementation (out of scope for this phase)

`RedisIdempotencyStore` in a new module `notification-idempotency-redis`,
activated when `spring-data-redis` is on the classpath and
`notification.idempotency.store=redis`. The SPI above is explicitly shaped
so Redis can back it without contract changes:

- `findExisting` → `GET`
- `markInProgress` → `SET ... NX EX <ttl>`
- `markComplete` → `SET ... EX <ttl>`
- `evictExpired` → no-op (Redis handles expiry)

### Semantics per state

When a request arrives with an `idempotencyKey`:

| Prior state | Behaviour |
|---|---|
| No prior record | Proceed. `markInProgress`. Dispatch. `markComplete`. |
| `IN_PROGRESS` | Return **HTTP 409 Conflict** with `{ "notificationId": "...", "status": "IN_PROGRESS" }`. No provider call. |
| `COMPLETE` and prior status was `SENT`, `DELIVERED`, or `ACCEPTED` | Return the original `NotificationResponse`, **HTTP 200**. No provider call. No additional audit write beyond a `DUPLICATE_HIT` audit event. |
| `COMPLETE` and prior status was `FAILED` or `REJECTED` | **Treat as fresh** — proceed to a new provider call. Both the replayed-failed original and the new attempt are written to audit so operators can correlate them by `idempotencyKey`. |

The `FAILED`-is-fresh rule deserves explanation and is the load-bearing
semantic choice in this DD:

- Transient provider failures (SMTP blip, SES throttle, Twilio 5xx) are the
  most common failure mode. A caller retrying with the same key usually
  *wants* a new attempt.
- Permanent-but-masquerading-as-transient errors (bad credentials, rejected
  recipient) would re-fail identically on retry, which is fine — the caller
  learns that the failure is durable by observing identical responses.
- Purists who want "once per key, success or failure" can layer that policy
  externally by not retrying on FAILED; the service does not enforce it.

This is a deliberate weakening of strict idempotency in favour of
operational ergonomics, called out explicitly in §Consequences.

### Integration into `DefaultNotificationService.send`

Before any provider work:

```java
IdempotencyKey key = request.getIdempotencyKey() == null ? null : new IdempotencyKey(
        request.getTenantId(), request.getCallerId(), request.getIdempotencyKey());

if (key != null) {
    Optional<IdempotencyRecord> existing = idempotencyStore.findExisting(key);
    if (existing.isPresent()) {
        IdempotencyRecord rec = existing.get();
        if (rec.status() == IdempotencyStatus.IN_PROGRESS) {
            throw new IdempotencyInProgressException(rec.notificationId());
        }
        NotificationResponse cached = rec.response();
        if (cached.status() == NotificationStatus.SENT
                || cached.status() == NotificationStatus.DELIVERED
                || cached.status() == NotificationStatus.ACCEPTED) {
            auditService.recordDuplicateHit(request, rec);
            return cached;
        }
        // fall through: prior attempt FAILED/REJECTED, treat as fresh
    }
    if (!idempotencyStore.markInProgress(key, request.getRequestId())) {
        // Lost the race. Re-read to learn the in-progress id; return 409.
        IdempotencyRecord concurrent = idempotencyStore.findExisting(key).orElseThrow();
        throw new IdempotencyInProgressException(concurrent.notificationId());
    }
}

try {
    NotificationResponse response = /* existing dispatch path */;
    if (key != null) idempotencyStore.markComplete(key, response);
    return response;
} catch (Throwable t) {
    // Ensure a failed dispatch also completes the key so we don't leak
    // IN_PROGRESS entries. Recorded status is FAILED, which per the table
    // above is treated as fresh on retry.
    if (key != null) {
        NotificationResponse failed = NotificationResponse.failed(request, null,
                "INTERNAL_ERROR", t.getMessage(), /* receivedAt */ null);
        idempotencyStore.markComplete(key, failed);
    }
    throw t;
}
```

`IdempotencyInProgressException` is a new checked exception in
`notification-api`, caught by `NotificationController` and converted to
HTTP 409.

### REST API behaviour

`POST /api/v1/notifications`:

- No `idempotencyKey` in body → current behaviour.
- `idempotencyKey` + first request → 200 with real response (existing
  behaviour).
- `idempotencyKey` + duplicate completed → 200 with the original response
  payload verbatim. Response header `X-Idempotent-Replay: true` added so
  callers can distinguish a real send from a cached response.
- `idempotencyKey` + duplicate in-flight → 409 Conflict with body:
  ```json
  { "notificationId": "<prior-id>", "status": "IN_PROGRESS" }
  ```

### Configuration

```yaml
notification:
  idempotency:
    enabled: true                # if false, the field on the request is
                                 # silently ignored (compatibility knob)
    ttl: P1D                     # ISO-8601 duration, default 24h
    max-entries: 100000          # Caffeine bound, ignored by Redis impl
    store: caffeine              # caffeine | redis (redis in a later phase)
```

`enabled: false` is the kill-switch for operators who encounter an
emergency and want to disable dedup without a code change.

## Reasoning

### Why make idempotency key-driven, not body-driven

- Body hashing (MD5 of the request JSON) is tempting but breaks when the
  caller legitimately wants to retry with a slight change (e.g. a longer
  timeout, different `correlationId`) while keeping the send intent the
  same. Caller-chosen keys give the caller control over what counts as "the
  same logical send."
- Body hashing also forces the service to canonicalise JSON before hashing,
  which is fragile across Jackson versions and locale settings.

### Why scope per `(tenantId, callerId, idempotencyKey)`

- Two tenants could legitimately use the same idempotency key (e.g. both
  call their send-order-confirmation flow with key `order-12345`). If we
  didn't scope per tenant, tenant A's request could mask tenant B's.
- Two callers within one tenant could similarly collide (e.g.
  `order-service` and `billing-service` both use `invoice-42`). Including
  `callerId` in the scope keeps them separate.
- Pre-DD-11 (`callerId = null`), keys are per-tenant only. When DD-11
  lands and callers start populating `X-Service-Id`, new keys start
  including the caller and old keys continue to work. No migration
  required, because keys are short-lived (TTL 24h).

### Why an SPI with a Caffeine default

- The briefing mandates library-mode usability. Requiring Redis for
  idempotency would make the starter useless for small consumers.
- The same briefing calls out a future Redis store. Defining the contract
  up-front ensures the Redis implementation won't need to fight the API
  shape.
- Caffeine's `putIfAbsent` on `asMap()` gives us atomicity without pulling
  in Guava's heavier cache abstraction.

### Why TTL = 24h by default

- 24h is long enough to catch operator-manual retries after a paging
  incident but short enough to keep the in-memory cache bounded.
- 24h is also the TTL used by Stripe, GitHub, and most public APIs that
  expose an `Idempotency-Key` header. Familiarity reduces surprise for
  callers.
- Fully configurable per-deployment via `notification.idempotency.ttl`.

### Why `X-Idempotent-Replay` header on replays

- Callers sometimes have side-effects keyed off notification success — e.g.
  "after the email sends, move the ticket to Resolved." A blind 200 from
  the cache could trigger the side-effect twice. The header lets them
  distinguish "I actually just caused a send" from "I'm seeing the result
  of a past send."
- Strictly, the caller could compare `sentAt` timestamps, but an explicit
  boolean is clearer and harder to get wrong.

### Why FAILED-is-fresh, not FAILED-is-cached

See the state table's explanation above. Summary: operational ergonomics
beats strict formal idempotency here. The audit trail preserves full
traceability of both attempts.

## Alternatives considered

### Alternative 1: body-digest idempotency (no explicit key)

Hash the serialised request body (excluding tracing fields) and use that as
the key. Rejected because:

- Canonical JSON is fragile; Jackson version differences silently change
  hashes.
- Removes caller control over what "the same request" means.
- Doesn't compose with batch sends, where sub-requests would all hash
  differently.

### Alternative 2: TTL = 7 days

Longer window, more dedup. Rejected because:

- Caffeine cache would either grow unbounded (memory risk) or evict by LRU
  before TTL expires (semantic surprise).
- Operators retrying by hand a week after an incident is vanishingly rare.
- Aligns us poorly with industry defaults (Stripe: 24h).

### Alternative 3: idempotency key REQUIRED

Reject requests without a key. Rejected because:

- Breaks backward compatibility with every existing caller.
- Forces callers who don't care about retries to generate and track a
  unique id on each send.

### Alternative 4: store the key in audit, use audit for dedup

Skip the separate SPI entirely; query `NotificationAudit` for
`(tenantId, idempotencyKey)` on every send. Rejected because:

- Adds a DB round-trip to the hot path on every keyed send.
- Couples idempotency to the audit-persistence module being enabled (see
  DD-07); today audit is optional.
- Ties the TTL semantics to audit retention, which is a different decision.

### Alternative 5: persist the full request payload, not just the response

Store the full original `NotificationRequest` alongside the response, so
replays can include the original send's template data. Rejected because:

- `NotificationRequest` often contains PII (email addresses, phone numbers,
  template variables with names/addresses/order details). Storing them for
  24h in memory — or 24h in Redis — materially widens the privacy blast
  radius for questionable gain.
- The response alone carries `requestId`, `correlationId`, `tenantId`,
  `provider`, `providerMessageId`, status, and timestamps — sufficient for
  the caller to learn that their retry was a duplicate.

## Consequences

### Positive

- Callers can safely retry transient failures without causing duplicate
  sends.
- Kafka at-least-once consumers deduplicate naturally when each producer
  includes an idempotency key.
- Standard industry pattern; fits what Stripe / GitHub / OpenAI APIs do.
- Library-mode consumers pay zero extra cost (in-memory default).
- Redis upgrade path is clean — SPI is Redis-shaped already.

### Negative

- In-memory default does **not** deduplicate across replicas in a
  service-mode deployment. Multi-replica users must configure the Redis
  store (once available) or front the service with a load balancer that
  pins by idempotency key (ugly, not recommended). Called out in README.
- FAILED-is-fresh weakens strict idempotency semantics. Callers who assume
  "one key, one observable outcome" will be surprised by replayed retries
  that succeed where the original failed. The caller-visible contract
  (summary in §Reasoning) is the documentation for this.
- `markInProgress` / `markComplete` add two cache operations per keyed
  send. In-memory cost is sub-microsecond; Redis cost is two round-trips.
  Users who disable idempotency via `notification.idempotency.enabled=false`
  pay zero.
- Caffeine eviction can surprise operators: if `max-entries` is hit before
  TTL, least-recently-used entries are dropped, so a request for a key
  whose record was evicted will be treated as fresh. Default of 100k
  entries absorbs ~1,000 keys/min for 100 minutes, which is well above a
  sensible retry cadence.

## Related decisions

- [DD-02 Notification Request Structure](./02-notification-request.md) —
  the `idempotencyKey` field is added here as an optional `@Size(max=255)`
  string.
- [DD-03 Multi-Tenancy](./03-multi-tenancy.md) — `tenantId` is the first
  component of the scope.
- [DD-07 Audit Persistence](./07-audit-persistence.md) — a
  `DUPLICATE_HIT` audit event is added so operators can trace replays
  distinct from first-time sends.
- **DD-11 Caller Identity** (not yet written) — `callerId` will join the
  scope. This DD pre-commits to the shape so DD-11 is purely additive.

## Rollout plan

1. Land this DD (this PR).
2. Follow-up PR: add `idempotencyKey` field to `NotificationRequest`,
   `IdempotencyStore` SPI to `notification-api`, `CaffeineIdempotencyStore`
   to `notification-core`, wire via auto-configuration in the starter.
3. Follow-up PR: integrate into `DefaultNotificationService.send` and
   expose the 200-replay / 409-in-flight behaviour through
   `NotificationController`.
4. Follow-up PR: unit tests for the Caffeine store (race, TTL, eviction),
   integration test covering both 200-replay and 409-in-flight.
5. README REST-API section updated to document the new field, the 409
   status, and the `X-Idempotent-Replay` header.

Redis implementation is explicitly deferred to a later phase.
