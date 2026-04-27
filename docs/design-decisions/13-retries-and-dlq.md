# Decision 13: Retries + Dead-Letter Queue

## Status: DECIDED

## Context

Provider calls fail. Some failures are worth retrying (Twilio rate-limit
response, SES throttling, transient TCP timeouts, 5xx); others should
not be retried (invalid recipient, unauthorized, malformed payload, 4xx
that aren't 429). Today the service does neither — a `failure()` from
the provider becomes a `FAILED` `NotificationResponse` and that's the
end of it. Operators have to retry from the caller side, which:

- Doesn't help the Kafka consumer path (that just commits the offset).
- Forces every caller team to implement their own retry policy with
  inconsistent backoff.
- Loses the audit trail across retries — separate `requestId`s look
  like separate sends.

We want the notification service itself to retry transient failures
with sensible backoff, and to record permanently-failed requests in a
dead-letter store so operators can inspect / reprocess them.

## Decision

Introduce two opt-in features in one PR:

1. **In-process retry with exponential backoff + jitter**, classified
   per failure type.
2. **Dead-letter store SPI** with a default in-memory implementation.
   Notifications that exhaust retries OR are classified as permanent
   failures get pushed to the DLQ.

Both default to off so existing deployments are unaffected.

### Failure classification

A new enum `FailureType` is added to `notification-api`:

```java
public enum FailureType {
    /** Worth retrying — provider is temporarily refusing. */
    TRANSIENT,
    /** Will not succeed on retry — bad input, permanent rejection. */
    PERMANENT,
    /** Provider didn't classify; defer to the configured RetryPredicate. */
    UNKNOWN
}
```

`SendResult` gains a `failureType` component. Providers populate it
when they have signal:

- HTTP 4xx (except 408, 425, 429) → `PERMANENT`
- HTTP 5xx, 408, 425, 429 → `TRANSIENT`
- I/O exceptions, timeouts → `TRANSIENT`
- Auth / config errors → `PERMANENT`
- Anything the provider can't classify → `UNKNOWN`

Existing factories (`SendResult.failure(...)`) default to `UNKNOWN` so
the public API stays backwards-compatible.

The decision of "retry or not?" is delegated to a `RetryPredicate` SPI:

```java
public interface RetryPredicate {
    boolean shouldRetry(SendResult result, int attempt);
}
```

Default predicate: retry on `TRANSIENT` and `UNKNOWN`, do not retry on
`PERMANENT`. Operators can swap in a custom one (e.g. opt out of
`UNKNOWN` retries, or add an attempt cap that varies per channel).

### Retry policy

Configuration:

```yaml
notification:
  retry:
    enabled: false               # off by default
    max-attempts: 3              # total attempts including the first
    initial-delay: PT1S          # first backoff window
    multiplier: 2.0              # exponential growth factor
    max-delay: PT30S             # cap on any single delay
    jitter: 0.5                  # 0..1, fraction of delay randomized ±
```

Backoff schedule: `delay(n) = min(initialDelay × multiplierⁿ, maxDelay)`,
then perturbed by ±`jitter`× of itself. Jitter is critical to avoid
thundering-herd on shared providers when many tenants retry the same
provider outage simultaneously.

Implementation: a small in-process `RetryExecutor` helper rather than
adding Resilience4j. The semantics we need are narrow (one retry loop
on a single callable), the dependency footprint matters (we're
already at Caffeine + Bucket4j + Hibernate Validator), and writing it
ourselves keeps the operational surface small.

### Where retries happen

Inside `DefaultNotificationService.send()`, the provider call is
wrapped in `RetryExecutor.execute(...)`. Retries happen **synchronously
in-process**. The send() call doesn't return until either:

- The provider succeeds, OR
- The classifier says "stop", OR
- `max-attempts` is exhausted.

Implications:

- For the **REST path**, the caller waits for the full retry window.
  This is the desired behaviour — REST callers want a final answer,
  not "I'll figure it out later".
- For the **async REST path** (`/notifications/async`), retries happen
  on the virtual-thread pool. The caller already has its `ACCEPTED`
  response.
- For the **Kafka path**, retries happen on the consumer thread before
  the offset is committed. This means a partition that has a
  consistently-failing message will block until retries are exhausted
  (and then the message ends up in DLQ). Operators can tune
  `max-attempts` to balance throughput vs. completeness.

### Interaction with rate limiting (DD-12) and idempotency (DD-10)

- **Rate limit**: consumed once per `send()` call, NOT per retry.
  Otherwise a single transient failure on a tightly-budgeted bucket
  would burn the whole bucket retrying. This means the limiter sees
  one token per "logical send", and the retry loop is internal.
- **Idempotency**: `markInProgress` is called once before the retry
  loop, `markComplete` is called once at the end. The IN_PROGRESS
  lock is held for the entire retry window — concurrent duplicates
  see the original requestId and 409, exactly as DD-10 specifies.
- **Order in the pipeline**:

```text
enrichRequest → idempotency-replay short-circuit → rate-limit check
              → markInProgress → [retry loop: render → provider.send]
              → markComplete
              → if failed: push to DLQ (if enabled)
              → audit
```

### Dead-letter store

A new SPI in `notification-api`:

```java
public interface DeadLetterStore {

    void record(DeadLetterEntry entry);

    /** Snapshot for the admin endpoint — Redis-backed impls may return Optional.empty(). */
    Optional<List<DeadLetterEntry>> snapshot();
}

public record DeadLetterEntry(
        Instant timestamp,
        NotificationRequest request,    // captured before any retry
        NotificationResponse response,  // final failed response
        int attempts,                   // how many retries were taken
        FailureType failureType         // why we gave up
) {}
```

Default implementation: `InMemoryDeadLetterStore` in `notification-core`
backed by a Caffeine bounded LRU cache (default max 1 000 entries, no
TTL — the DLQ is for *operators to inspect*, not for prod
state-of-the-world). Bounded so a misbehaving provider can't blow up
heap.

What gets pushed to the DLQ:

- A `PERMANENT` failure (no retries attempted).
- An `UNKNOWN` or `TRANSIENT` failure that exhausted `max-attempts`.

What does NOT go to the DLQ:

- Successful sends.
- `IdempotencyInProgressException` — that's a 409, not a failure.
- `RateLimitExceededException` — that's a 429, the caller's choice.
- Validation errors before provider dispatch (e.g. unknown tenant).

### Admin endpoint

`GET /api/v1/admin/dead-letter` — returns the snapshot when the
backing store supports it (the in-memory default does):

```json
{
  "enabled": true,
  "size": 12,
  "entries": [
    {
      "timestamp": "2026-04-27T10:42:01Z",
      "tenantId": "acme",
      "callerId": "billing-svc",
      "channel": "EMAIL",
      "requestId": "req-abc-123",
      "attempts": 3,
      "failureType": "TRANSIENT",
      "errorCode": "PROVIDER_TIMEOUT",
      "errorMessage": "smtp 421 — try again later"
    }
  ]
}
```

The admin response intentionally does **not** include the full
`NotificationRequest` (template data may carry PII). The full request
is held by the in-memory store but is not surfaced through the admin
API. A future "DLQ replay" endpoint will re-submit by request id, not
echo data.

### Out of scope

- **Replay from DLQ.** Useful next step but its own design — needs
  authorization, audit, and a `replay-of` reference on the new
  request. Tracked in PROGRESS.md.
- **Persistent DLQ** (Redis / Kafka topic / S3). The SPI is shaped so
  these can plug in; an in-memory default is enough to ship retries
  meaningfully today.
- **Retry in the Kafka publisher path.** Producers that put messages
  on the notification topic already have their own retry semantics.
  The retry policy here is for the *provider* call, not the upstream
  ingestion.
- **Per-channel retry config.** All channels share one `retry.*` block
  in this DD. If operators need SMS to retry differently from email,
  they can do it via a channel-specific `RetryPredicate` swap-in. Not
  enough demand yet to make this configurable.

## Reasoning

### Why opt-in

Same logic as DD-10/DD-11/DD-12. Existing 1.0.x deployments are in
production; adding latency (retry waits) by default would surprise
callers. `enabled: false` is the safe default. Operators flip it on
once they've tuned `max-attempts` for their throughput SLA.

### Why classify failures rather than retry-everything

Retrying a `PERMANENT` failure (e.g. invalid recipient) wastes time
and money. Twilio charges for *attempts*, not just successes —
retrying an "Invalid To Number" three times is three billable units
for a guaranteed-failure outcome. The classifier is the cost-control
boundary.

### Why exponential backoff with jitter

- **Exponential** because most provider issues self-resolve on a
  longer timeline (rate-limit windows are 1s–60s, deploys are
  multi-minute). Linear backoff doesn't scale with outage duration.
- **Jitter** because every notification-service pod retrying the same
  provider on the same schedule = thundering herd at the exact
  moment the provider comes back. Decorrelated jitter (±50% by
  default) spreads retries across a window, smoothing recovery.

### Why synchronous (in-process) retries

Considered: queueing failed sends to a separate retry topic /
DelayQueue / scheduled executor pool.

- **Pro**: caller doesn't wait, throughput stays high.
- **Con**: now we have distributed retry state. Need a backing store
  for in-flight retries, recovery on pod restart, ordering vs new
  sends.

The synchronous path is simpler, easier to reason about, and the
caller (REST or Kafka) already has a thread for this work. For very
long retry windows operators can use the existing `/notifications/async`
endpoint, which already runs send() on a virtual thread.

### Why a single retry-budget for the whole send (not per-attempt)

If we made each attempt its own rate-limit consumer, a 5xx outage
would drain the bucket in N retry cycles. The bucket is meant to
protect downstream cost, and the *logical send* is what costs. Same
reasoning: idempotency lock is held once for the whole retry window.

### Why an in-memory DLQ default

A persistent DLQ is the right answer for production ops, but it's a
separate decision (Redis? Kafka topic? S3?). Shipping retries today
without any DLQ would mean retry-exhausted notifications vanish into
WARN logs. An in-memory bounded ring is good enough for development,
single-pod deployments, and as a "what's broken right now?"
operator view. The SPI is shaped so the persistent versions plug in
without changing service code.

## Consequences

### Positive

- Transient provider blips no longer manifest as user-visible
  failures.
- Operators get a "what failed and why?" view via the admin DLQ.
- Per-DD-12 cost-control invariants (one rate-limit token per
  logical send) are preserved.
- Provider-specific intelligence (which 4xxs are permanent) lives in
  the provider module, not in the service — keeps the abstraction
  clean.

### Negative

- REST sync callers experience longer p99 when retries fire. We
  mitigate by capping `max-delay` and exposing per-call latency via
  the existing audit trail (a future enhancement: a `attempts` count
  on `NotificationResponse`).
- The Kafka consumer thread blocks during retry windows. Operators
  with strict throughput SLAs should keep `max-attempts` low and rely
  on the DLQ for terminal failures.
- One more configurable dimension. Mitigated by sensible defaults
  and the off-by-default state.

### Migration path

| Phase | Action                                                         |
|-------|----------------------------------------------------------------|
| Now   | DD-13 ships, `enabled: false` for both retry and DLQ           |
| +T1   | Operators flip retry on with conservative `max-attempts: 2`    |
| +T2   | Tune backoff based on observed `attempts` in DLQ               |
| +T3   | Provider modules opt in to `FailureType` classification        |
| +T4   | Replace in-memory DLQ with persistent (Redis / Kafka) backend  |

## Alternatives Considered

### Alternative 1: Resilience4j retry library

- **Rejected for now**: brings transitive deps (vavr, resilience4j-core,
  resilience4j-circuitbreaker), and the semantics we need are narrow
  enough for ~50 lines of in-process code. We can swap in
  Resilience4j later if we need bulkhead / circuit-breaker semantics
  that don't fit the simple loop.

### Alternative 2: Asynchronous retry via a delay queue

- **Rejected**: solves a problem (non-blocking caller) the existing
  `/notifications/async` endpoint already addresses. Adds distributed
  state we don't yet need.

### Alternative 3: Always retry, ignore classification

- **Rejected**: wastes provider quota on guaranteed-failure outcomes,
  costs real money on paid channels. Classification is the
  cost-control boundary.

### Alternative 4: Retry at the Kafka consumer level (delay-topic pattern)

- **Rejected**: consumer-level retries don't help the REST path.
  We want one retry policy that covers both transports — mirrors
  DD-11's "uniform across REST and Kafka" rule.

## Related Decisions

- [10-idempotency.md](./10-idempotency.md) — idempotency lock semantics
  during retry windows.
- [12-rate-limiting.md](./12-rate-limiting.md) — token consumption is
  per-logical-send, not per-retry.
- [11-caller-identity.md](./11-caller-identity.md) — DLQ entries
  include `callerId` for traceability.
