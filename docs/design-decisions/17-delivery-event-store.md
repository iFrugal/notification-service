# Decision 17: Persistent DeliveryEventStore

## Status: DECIDED

## Context

DD-16 shipped the webhook ingestion surface but stopped short of a
persistence story:

> Persistent `DeliveryEventStore` SPI [is] Out of scope. Mirroring the
> DD-13 DLQ shape — bounded in-memory default, plug-in Redis/SQL/Kafka.
> Good follow-up but we ship the listener seam first; persistence is
> a matter of who's listening.
> — DD-16 §"Out of scope"

That punted on a real operator problem: out of the box, the default
`LoggingDeliveryEventListener` logs each callback and that's it. To
answer "did `req-abc-123` ever get delivered?" an operator has to
either:

1. Have already wired up a custom `DeliveryEventListener` that
   persists somewhere, **or**
2. Grep their JSON logs.

Neither is great. The webhook surface only earns its keep when there's
a way to query what came in.

## Decision

Add an opt-in `DeliveryEventStore` SPI alongside DD-16's
`DeliveryEventListener`. The store *is* a listener (via a default
method on the interface), so registering a store bean automatically
joins the `List<DeliveryEventListener>` the webhook controller already
injects — no glue auto-config needed.

Three pieces ship together:

1. **`DeliveryEventStore` SPI** on `notification-api`. Methods: `add`,
   `snapshot`, `size`, `findByProviderMessageId`. Default
   `onEvent(event)` calls `add(event)` so the interface composes
   cleanly with the existing listener machinery.
2. **`InMemoryDeliveryEventStore`** default in `notification-core`.
   Caffeine bounded LRU, same shape as `InMemoryDeadLetterStore`
   (DD-13). Off by default — single-pod operators flip
   `notification.delivery-events.enabled=true` and immediately get
   `GET /admin/delivery-events`.
3. **`RedisDeliveryEventStore`** in `notification-redis`. `LPUSH` +
   `LTRIM` against a bounded list, `LRANGE` for snapshot, scan for
   `findByProviderMessageId`. Same pattern as `RedisDeadLetterStore`
   (DD-14). Multi-pod operators flip
   `notification.redis.delivery-events.enabled=true` to share the
   buffer across pods.

A new admin endpoint `GET /admin/delivery-events` exposes the store
for operator inspection.

### SPI shape

```java
public interface DeliveryEventStore extends DeliveryEventListener {

    /** Persist a single event. Implementations must never throw. */
    void add(DeliveryEvent event);

    /** Default bridge — registering a store bean is enough to wire it
     *  into the DeliveryEventListener fan-out. */
    @Override
    default void onEvent(DeliveryEvent event) {
        add(event);
    }

    /** Most-recent-first snapshot. Optional.empty when the backend
     *  can't iterate cheaply (parallel to DeadLetterStore.snapshot). */
    Optional<List<DeliveryEvent>> snapshot();

    /** Convenience lookup — most recent N events for a single
     *  notification's providerMessageId. The join key from the
     *  original SendResult. */
    Optional<List<DeliveryEvent>> findByProviderMessageId(
            String providerName, String providerMessageId);

    int size();
}
```

### Why extends `DeliveryEventListener` rather than a separate type

Considered three shapes:

**Option A** — `DeliveryEventStore` standalone, with an autoconfig
adapter that wraps it as a `DeliveryEventListener`. Mechanically fine
but adds a bean nobody asked for, and the indirection makes "what
happens to my event?" harder to trace.

**Option B** — `DeliveryEventStore` and `DeliveryEventListener`
totally independent SPIs that operators wire together themselves.
Pushes glue code onto every consumer; ergonomically bad.

**Option C** (chosen) — `DeliveryEventStore extends DeliveryEventListener`
with a default `onEvent` calling `add`. One annotation
(`@Component`), the bean satisfies both the listener fan-out and the
admin endpoint's store lookup. Composes with custom listeners — an
operator with their own audit pipeline registers it alongside the
default store, and both get events.

The trade-off is conceptual: "is a store also a listener?" Yes, in
the sense that an `add`-only consumer is the trivial case of an
event listener. No new behaviour, just an adapter encoded at the
type level rather than at the bean-graph level.

### Why not merge with the DD-13 DLQ store

Different lifecycles, different consumers, different bounds. DLQ
entries are "send failed terminally" — they're rare and operators
want to act on them (replay). Delivery events are "the provider told
us something happened later" — they're common (one per send minimum)
and operators want to *query* them, not act on each one. Sharing the
bound would mean tuning the same `max-entries` for two very different
arrival rates.

Also, semantically: a delivery event for a *successful* send is a
non-error signal; lumping it into a "dead-letter queue" admin endpoint
would confuse operators who navigate by terminology.

### Admin endpoint

```
GET /admin/delivery-events
GET /admin/delivery-events?providerName=ses&providerMessageId=ses-msg-1
GET /admin/delivery-events?limit=50
```

Response shape mirrors `/admin/dead-letter`:

```json
{
  "enabled": true,
  "maxEntries": 5000,
  "size": 42,
  "entries": [
    {
      "timestamp": "2026-05-06T18:42:01Z",
      "providerName": "twilio",
      "providerMessageId": "SM1abc",
      "providerEventId": "AC1:SM1abc:delivered",
      "status": "DELIVERED",
      "reason": null
    }
  ]
}
```

Raw provider attributes are **excluded** from the response by default —
they can carry recipient identifiers (phone numbers, email addresses)
that operators don't always want surfaced in admin tooling. Operators
opt in with `?includeRaw=true`; the response gains an `attributes`
field per entry. Matches the DD-13 §PII redaction stance on the DLQ
endpoint.

`503 Service Unavailable` when
`notification.delivery-events.enabled=false`, same convention as the
DLQ endpoint.

### Configuration

```yaml
notification:
  delivery-events:
    enabled: false           # master switch; default off
    max-entries: 5000        # in-memory bound
  redis:
    delivery-events:
      enabled: false         # turns on RedisDeliveryEventStore
      max-entries: 10000     # Redis LIST cap
```

Higher default cap than the DLQ (5000 vs 1000) because delivery events
arrive at a rate proportional to send volume — a thousand-event
buffer would wrap on any meaningful traffic.

### Bounds and eviction

In-memory: Caffeine maximum-size; oldest entries evicted lazily,
deterministic at snapshot time via the same monotonic-counter trick
`InMemoryDeadLetterStore` uses.

Redis: `LTRIM 0 maxEntries-1` after every `LPUSH`. Race-window
overshoot is bounded as documented on `RedisDeadLetterStore` —
acceptable for an operator-inspection surface.

## Out of scope

- **Long-term archival.** Both stores are inspection surfaces — bounded
  in size, no TTL. Operators who need 90-day delivery history wire
  their own listener that writes to S3 / data warehouse. The DD-17
  store is for "what just happened?" not "what happened last quarter?"
- **Join with `NotificationAudit`.** A delivery event has a
  `providerMessageId`; the audit record has the same field. A future
  DD can offer a joined view at the admin endpoint. We don't ship
  that here because the audit SPI is itself pluggable and not always
  populated.
- **Webhook for outbound delivery state to upstream callers.** Same
  reasoning as DD-16 §"Out of scope" — callers' reverse-webhook
  needs are downstream of having state at all.
- **DeliveryEventStore replay.** Replay is meaningful for the DLQ
  (DD-15) because DLQ entries are "things still broken." A successful
  delivery event isn't replayable; a `BOUNCED` event might motivate
  retry from the caller, but the bounce itself is information, not
  an action.

## Reasoning

### Why opt-in

Same logic as every other SPI on this service. Existing deployments
shouldn't suddenly start retaining delivery state in process memory.
The master switch + per-feature toggle gives incremental adoption.

### Why bound at all

In-process retention of every delivery event for a high-volume
service would leak memory until the next restart. The bound +
operator-tunable max-entries is the same trade-off the DLQ makes:
"recent enough to be useful, bounded enough to be safe."

### Why `findByProviderMessageId` rather than full-text search

`providerMessageId` is the natural join key — every event from the
webhook surface carries it, and every successful send returns it. A
full-text or attribute-filtered search would require indexing the
attributes map, which is a different storage shape (search engine,
not a bounded list). Out of scope for now; operators with that need
should write events to a real index.

### Why mirror the Redis pattern rather than use a sorted set with TTL

A `ZSET` with `EXPIRE` per entry would give time-based eviction
naturally. But the cap-by-count semantics match the in-memory default
exactly, and `LTRIM` is one round-trip rather than scoring per
insert. The simpler implementation is enough for an operator surface.
