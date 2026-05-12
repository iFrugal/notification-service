# Decision 23: Per-Channel Retry + Rate-Limit Overrides

## Status: DECIDED

## Context

DD-12 (rate limiting) and DD-13 (retries + DLQ) shipped with single
global defaults. Their respective §"Out of scope" sections both
flagged that channels really need different numbers:

- **SMS** is expensive (each provider attempt is a billable unit) and
  bound to a hard carrier ceiling (Twilio caps SMS sends/sec per
  number). Operators want a tight `max-attempts: 2` and a low rate
  bucket.
- **Email** is cheap, providers are forgiving, and burst-tolerance
  matters for marketing sends. Operators want `max-attempts: 5`
  and a high bucket.

Today both have to use the same numbers. That's wrong in both
directions: tune for SMS, email is too conservative; tune for email,
SMS is too aggressive (cost) or too loose (carrier).

DD-12 already supports per-`(tenant, caller, channel)` overrides for
rate limit. But the natural operator intent — "set SMS defaults
differently from email, period" — currently requires enumerating
overrides for every tenant. Operationally this means
"the global default is wrong, the overrides table is enormous."

## Decision

Add a `byChannel` map to both `RetryProperties` and `RateLimitProperties`.
The map keys are channel names (lowercase, e.g. `sms`, `email`,
`push`); the values are rule objects of the same shape as the
existing top-level defaults.

```yaml
notification:
  retry:
    enabled: true
    max-attempts: 3              # global default (unchanged)
    initial-delay: 1s
    multiplier: 2.0
    max-delay: 30s
    jitter: 0.5
    by-channel:
      sms:
        max-attempts: 2          # tight bound on expensive provider
        initial-delay: 2s
      email:
        max-attempts: 5          # generous for cheap, transient-prone provider
  rate-limit:
    enabled: true
    default-rule:
      capacity: 200
      refill-tokens: 100
      refill-period: 1s
    by-channel:
      sms:
        capacity: 20             # carrier ceiling
        refill-tokens: 5
        refill-period: 1s
    overrides:
      - tenant: acme
        caller: marketing
        channel: sms
        capacity: 50             # this tenant gets a higher SMS bucket
        refill-tokens: 10
        refill-period: 1s
```

### Precedence (rate limit)

```
most-specific (tenant, caller, channel) override   ← existing
        > (tenant, caller) override
        > (tenant) override
        > byChannel default                         ← new
        > global defaultRule                        ← existing fallback
```

The `byChannel` slot sits *between* the tenant overrides and the
global default. This is the right place semantically: a `byChannel`
default says "globally, SMS is more bounded than the catch-all
default" — but a tenant who's negotiated a higher SMS bucket still
wins.

### Precedence (retry)

Retry has no per-tenant overrides (DD-13 §"Per-channel retry
config" explicitly chose simplicity). So the precedence is just:

```
byChannel rule for the request's channel   ← new
        > global defaults                  ← existing
```

### SPI / config layout

`RetryProperties` keeps its existing top-level fields
(`maxAttempts`, `initialDelay`, `multiplier`, `maxDelay`, `jitter`).
Those remain the global default — **no breaking config change** for
existing deployments. A new `byChannel: Map<String, RetryRule>` slot
holds optional overrides. `RetryRule` is a new value object with the
same five fields and validation; the global fields plus `byChannel`
together replace neither — they're complementary.

`RateLimitProperties` already has `defaultRule: RateLimitRule` and
`overrides: List<RateLimitOverride>`. The new slot is
`byChannel: Map<String, RateLimitRule>` — same `RateLimitRule` type
the overrides already extend.

### Lookup helpers

Both property classes get a small `ruleFor(channel)` helper that
returns the resolved rule. The executor / limiter call this helper
once per request and use the returned rule for all decisions on
that request. The helper does the case-insensitive channel-name
match against `byChannel`.

### Channel name matching

Channel names match case-insensitively (lowercase-folded for the
map key). YAML config under `notification.retry.by-channel.SMS`
is equivalent to `notification.retry.by-channel.sms`. The
`Channel` enum in `notification-api` is uppercase
(`EMAIL`, `SMS`, etc.); the lookup folds before comparing.

### RetryExecutor channel awareness

The current `execute(Supplier<SendResult>)` signature doesn't know
about the channel. Adding a channel-aware overload
`execute(Channel, Supplier<SendResult>)` is the minimal touch.
The old signature stays for API compatibility (just delegates with
a null channel → global rule).

`DefaultNotificationService` updates to call the new overload,
passing `request.getChannel()`.

## Out of scope

- **Per-channel DLQ bounds.** A single bounded buffer is fine —
  the DLQ is for inspection, not per-channel quota accounting.
- **Per-caller `byChannel` defaults** (e.g. "for caller X, SMS
  defaults to this rule across all tenants"). The existing
  override system covers this with `caller` + `channel` set
  + `tenant: *`-style would-be wildcards. Out of scope for now.
- **Per-channel idempotency TTL.** Idempotency keys don't have
  channel-shaped reasons to differ — the dedup tuple already
  includes channel via the request shape.
- **Per-channel master switch** ("disable retry entirely for SMS").
  Operators wanting this set `by-channel.sms.max-attempts: 1` —
  one attempt, no retries. Equivalent without a new boolean.

## Reasoning

### Why `byChannel` between specific overrides and global default
(rate limit)

Two valid orderings were considered:

**A** — global default → `byChannel` → overrides (chosen)
**B** — global default → overrides → `byChannel`

Option B reads "channel-specific bounds always trump tenant
agreements" which is operationally wrong. A tenant who pays for a
higher SMS bucket should get it; the channel-default is the
catch-all backstop, not a ceiling.

Option A puts `byChannel` exactly where it makes sense — between
"unspecific catch-all" and "specific tenant/caller-shaped tuning."

### Why not migrate top-level retry fields into a `defaultRule` object

Considered. The shape would be:

```yaml
notification:
  retry:
    default-rule:
      max-attempts: 3
      initial-delay: 1s
      ...
    by-channel:
      sms: ...
```

Rejected because it's a **breaking config change** for every existing
deployment. The top-level fields are the implicit `defaultRule`; the
new `byChannel` slot composes on top. Keeping both shapes valid is
the right ergonomic call.

### Why the RetryExecutor gets a new overload rather than reading
channel from `RequestContext`

Three reasons:

1. **Explicit > implicit.** The executor's job is to retry a
   provider call. The channel relevant for retry tuning is the
   request's `channel`, which the caller knows. Reading it from a
   ThreadLocal would be subtle action-at-a-distance.
2. **Testability.** `execute(Channel.SMS, supplier)` reads as
   straightforward setup. `RequestContext.set(...) + execute(supplier)`
   is brittle.
3. **The old `execute(Supplier)` overload stays.** Callers that
   don't have a channel (none currently — this is forward-only)
   keep working.

### Why don't we add per-channel DLQ retention

The DLQ is bounded for memory-pressure reasons, not for per-tenant
accounting. A "give SMS more space than email" knob would mean
operators have to think about which channel's failures matter more
to keep around — which isn't actually a question they want to make
config-time. They want all recent failures, bounded by total memory.
Keep DLQ uniform.
