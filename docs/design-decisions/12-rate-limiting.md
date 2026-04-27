# Decision 12: Rate Limiting (per tenant + caller + channel)

## Status: DECIDED

## Context

DD-11 just landed `callerId` as a first-class dimension. The natural next
abuse-control mechanism is rate limiting — we now have enough request
identity to throttle meaningfully:

- **Tenant-level overload protection.** A misbehaving customer running a
  loop should not be able to drown out other tenants on a shared
  deployment.
- **Per-caller throttling.** A specific service team going wild with
  retries shouldn't take down email/SMS spend for the whole tenant.
- **Per-channel ceilings.** SMS in particular has direct cost
  implications — operators want to bound it independently of email.

The notification service runs alongside paid provider integrations
(Twilio, SES, FCM). An unbounded retry loop can hit hard provider limits
in seconds and rack up real money. We need an in-process throttle that's
cheap to evaluate, configurable per dimension, and works uniformly
across the REST and Kafka transports.

## Decision

Add an opt-in, configurable rate limiter scoped over
`(tenantId, callerId, channel)`. The default state is **off** —
existing deployments are unaffected. Once enabled, requests that exceed
their bucket return HTTP **429 Too Many Requests** with a `Retry-After`
header on the REST path. On the Kafka path the message is skipped with
a WARN log and the consumer commits the offset (drop-and-commit) — at
least-once requeue would amplify the very pressure the limiter is
trying to relieve.

### Library

**Bucket4j 8.10.x** for the in-memory implementation. Pros: small, no
external dependencies in its core jar, supports token-bucket semantics
with arbitrary refill schedules, has a Redis backend for the future
distributed-deployment case (mirrors the DD-10 idempotency story).

The default bean is wired only when `notification.rate-limit.enabled=true`
so the dependency stays inert in deployments that don't care.

### SPI

Defined in `notification-api`:

```java
package com.lazydevs.notification.api.ratelimit;

public interface RateLimiter {

    /**
     * Try to consume a single token for this scope.
     *
     * @return a Decision indicating allow / deny + how long until the
     *         next token is available.
     */
    Decision tryConsume(RateLimitKey key);

    record RateLimitKey(String tenantId, String callerId, String channel) {}

    record Decision(boolean allowed, java.time.Duration retryAfter) {
        public static Decision allow() { return new Decision(true, Duration.ZERO); }
        public static Decision deny(Duration retryAfter) { return new Decision(false, retryAfter); }
    }
}
```

Same shape as `IdempotencyStore` — a single SPI in the api module,
default impl in core, Redis as a future second impl.

### Configuration

```yaml
notification:
  rate-limit:
    enabled: false                # off by default
    default:                      # applied when no override matches
      capacity: 200               # bucket size = burst tolerance
      refill-tokens: 100          # tokens added per refill period
      refill-period: PT1S         # ISO-8601 duration
    overrides:
      - tenant: acme              # required
        caller: billing-svc       # optional — omit for tenant-wide
        channel: sms              # optional — omit for all channels
        capacity: 50
        refill-tokens: 10
        refill-period: PT1S
      - tenant: acme
        caller: marketing-svc
        capacity: 1000
        refill-tokens: 1000
        refill-period: PT1S
```

**Match precedence (most specific wins):**

1. `(tenant, caller, channel)` exact match
2. `(tenant, caller)` — any channel
3. `(tenant)` — any caller, any channel
4. `default`

Each unique tuple gets its own Bucket4j bucket the first time it's
seen; buckets are stored in a `ConcurrentHashMap` keyed by the resolved
`(tenant, caller, channel)` triple. There's no eviction yet — the key
space is bounded by the operator's configuration size, not by request
volume.

### Enforcement points

The limiter is checked **inside `DefaultNotificationService.send()`** —
right after request enrichment, before the idempotency check. This
gives uniform coverage across REST, Kafka, and any future programmatic
caller. On deny:

- A `RateLimitExceededException` is thrown carrying the `retryAfter`.
- The REST `GlobalExceptionHandler` converts it to **HTTP 429** with the
  `Retry-After` header set to seconds-until-next-token.
- The Kafka listener catches it like any other exception, logs a WARN,
  and lets Kafka commit the offset (no requeue — at-least-once semantics
  with rate limiting would amplify the problem).

Order in the send pipeline:

```text
enrichRequest → idempotency-replay short-circuit → rate-limit check
              → fresh dispatch → audit
```

Note the limiter runs **after** the idempotency replay branch but
**before** the fresh dispatch. Replays of completed keys are returning
cached responses — they shouldn't burn rate-limit tokens. Genuinely new
work does. (This was clarified during DD-12 review — earlier drafts
described the limiter running before idempotency entirely.)

Why this ordering: replays of already-completed idempotency keys are
returning a cached response — they shouldn't burn rate-limit tokens.
But the limiter still runs before {`markInProgress` → fresh dispatch},
so a brand-new request that's about to do real provider work does pay a
token. The IN_PROGRESS path also runs before the limiter so the 409
path is cheap.

### Admin endpoint

`GET /api/v1/admin/rate-limit` — returns the configured limits and a
snapshot of currently-tracked buckets:

```json
{
  "enabled": true,
  "default": {"capacity": 200, "refillTokens": 100, "refillPeriod": "PT1S"},
  "overrides": [
    {"tenant": "acme", "caller": "billing-svc", "channel": "sms",
     "capacity": 50, "refillTokens": 10, "refillPeriod": "PT1S"}
  ],
  "activeBuckets": [
    {"tenant": "acme", "caller": "billing-svc", "channel": "email",
     "availableTokens": 187}
  ]
}
```

The active-bucket snapshot is a debug aid — operators can see at a
glance which keys are seeing traffic and how close they are to their
limit.

### Out of scope

- **Distributed rate limiting.** Bucket4j has a Redis backend, but
  wiring it in means coordinating with the same Redis investment that
  DD-10 has flagged for idempotency. We'll do both as one DD-13 once
  the operator is asking for multi-pod behaviour.
- **Per-recipient limits.** Throttling spam to a specific email address
  is a different concern from caller throttling and would touch the
  recipient model. Future DD if requested.
- **Adaptive / breaker semantics.** A circuit breaker that opens on
  provider failure and closes on success is related but distinct.
  Tracked separately in `docs/PROGRESS.md` Phase 6+.

## Reasoning

### Why scope = `(tenant, caller, channel)`

These are the three dimensions operators have asked about historically:

- Tenant: the billing boundary (which tenant is over budget?)
- Caller: the team accountability boundary (which service team is
  misbehaving?)
- Channel: the cost boundary (SMS dollars vs free email)

Adding a fourth (e.g. `notificationType`) would be diminishing returns
for a much larger config surface.

### Why opt-in

Same logic as the DD-11 caller registry: the existing `1.0.x` line is
in production. A throttle turned on by surprise would manifest as
unexpected 429s. Default off, opt-in via YAML, document the
configuration shape clearly.

### Why enforcement in the service rather than a filter

A REST-only filter wouldn't cover Kafka. A Kafka-only check wouldn't
cover REST. Enforcement in `DefaultNotificationService.send()` covers
everything that goes through the service contract — including future
gRPC, JMS, or programmatic embeddings.

### Why this ordering relative to idempotency

Three rules govern the placement:

1. **Replays of completed keys are exempt.** A caller hitting the cache
   is getting back the original send's response — they shouldn't pay a
   rate-limit token for that.
2. **IN_PROGRESS conflicts are exempt.** A 409 response is cheap by
   design; making the limiter gate it would slow down the cheap path
   for no benefit.
3. **Fresh sends pay.** Anything that actually causes a `markInProgress`
   → provider call → `markComplete` cycle goes through the limiter
   first.

The implementation runs the limiter inside the same block as the
idempotency check, after the replay/IN_PROGRESS short-circuits and
before `markInProgress`.

### Why Bucket4j

- Mature (10+ years), low CPU overhead (~50ns per `tryConsume`).
- Token-bucket semantics map cleanly to the YAML config.
- Has a Redis adapter for the future distributed case without changing
  the SPI.
- Apache 2.0, no licensing complications.

Considered alternatives: Resilience4j RateLimiter (more general, more
deps), Guava RateLimiter (no Redis story), hand-rolled
(maintenance burden).

## Consequences

### Positive

- Tenants and callers get protection from each other.
- 429 + `Retry-After` is the standard, well-understood signal — no
  custom protocol.
- Same SPI shape as DD-10 idempotency, so the Redis-backed extension is
  cookie-cutter when the time comes.
- Rate-limit decisions show up in the admin endpoint for ops visibility.

### Negative

- One more configurable dimension. We mitigate with sensible defaults
  and the override-precedence rule.
- Bucket maps grow with the unique-tuple count. Bounded by config size,
  not request volume — but if an operator configures `tenants × callers
  × channels` overrides, the map has a known maximum. Documented.

### Migration path

| Phase | Action                                                              |
|-------|---------------------------------------------------------------------|
| Now   | DD-12 ships, `enabled: false` by default                            |
| +T1   | Operators populate `default` + a few overrides, flip enabled        |
| +T2   | Tune limits based on the admin-endpoint snapshot of active buckets  |

## Alternatives Considered

### Alternative 1: Servlet filter / interceptor only (REST-side)

- **Rejected**: doesn't cover Kafka. The whole point of moving caller
  identity into the request body in DD-11 was uniform handling across
  transports.

### Alternative 2: Apply rate limit per recipient (e.g. per email
address)

- **Deferred**: legitimate operator concern but a different abstraction
  layer. Touches the polymorphic Recipient model. Will revisit if
  asked.

### Alternative 3: Block at provider call instead of upstream

- **Rejected**: provider rate limits are different from operator rate
  limits. We want operators to set their own ceilings, well below
  provider hard limits, with their own SLA / cost discipline.

## Related Decisions

- [03-multi-tenancy.md](./03-multi-tenancy.md) — `tenantId` dimension.
- [10-idempotency.md](./10-idempotency.md) — same SPI shape for the
  pluggable backing store.
- [11-caller-identity.md](./11-caller-identity.md) — `callerId`
  dimension this DD reuses.
