# Decision 30: Rate-Limit Bandwidths, Bucket Scope and Refunds

## Status: DECIDED

## Context

DD-12 gives every `(tenant, caller, channel)` one token bucket with a single band: `capacity`, `refill-tokens`, `refill-period`.
DD-23 added per-channel defaults.
Three needs do not fit that shape.

**Several limits at once.**
Provider quotas are often stated as two limits, for example 2 messages per minute and 5 per hour.
One band can express only one of them.

**A quota shared by everyone.**
A provider account quota applies to the whole application, not to each tenant and caller.
With one bucket per `(tenant, caller, channel)`, ten tenants get ten times the account quota.

**Tokens spent on sends that never happened.**
The limiter consumes a token before the request is dispatched.
When the request then fails before it reaches a provider (a missing template, an unknown provider, an unbuildable provider), the token is gone although nothing was sent.

## Decision

### Bandwidths

`RateLimitRule` gains `bandwidths`, a list of `{capacity, refill-tokens, refill-period}` (`RateLimitBandwidth`).
A send is allowed only when every band has a token.
When `bandwidths` is empty, the single-band fields are used as before.
Setting `bandwidths` together with any of `capacity`, `refill-tokens` or `refill-period` fails startup with "rate-limit rule sets both 'bandwidths' and 'capacity'/'refill-tokens'/'refill-period'; use one or the other".
The check counts a single-band field as set when configuration binding calls its setter, so field defaults and the `RateLimitRule(long, long, Duration)` constructor do not count.
Each band is validated like a rule: capacity and refill tokens at least 1, refill tokens not above capacity, a positive refill period.
Both limiters build one Bucket4j greedy `Bandwidth` per band.
The `Retry-After` of a denial is Bucket4j's time until one token is available in every band.

### Scope

`RateLimitRule` gains `scope`: `TENANT_CALLER` (default, the 1.2.0 key) or `CHANNEL`.
The limiter first resolves the rule with the existing precedence, then the winning rule's scope picks the bucket:

- `TENANT_CALLER`: one bucket per `(tenant, caller, channel)`.
- `CHANNEL`: one bucket per channel shared by every request that resolves to that rule.
  For `default-rule` and `by-channel.<channel>` that is every tenant and caller without a more specific override, which is the provider-account quota.
  For an override it is every request the override matches, for example all callers of one tenant.

Because the rule is resolved first, an override still matches by tenant and caller when the channel rule is shared: a tenant with its own override gets that override's bucket, not the shared one.
The scope is applied inside the limiter, so `DefaultNotificationService` and the `RateLimitKey` it builds are unchanged.

Bucket keys:

- In memory, a shared bucket is cached under a key that cannot equal a tenant's key, and `Bucket4jRateLimiter.snapshot()` lists it as `("*", <ruleId>, <channel>)`.
- In Redis, a tenant bucket keeps the 1.2.0 key `<prefix>:ratelimit:<tenant>:<caller>:<channel>`; a shared bucket uses `<prefix>:ratelimit-shared:<ruleId>:<channel>`.
- `ruleId` is `default`, `by-channel:<channel>` or `override:<index>`, the override's position in configuration order.

The precedence and bucket configuration now live in one class, `RateLimitRuleResolver`, used by both limiters.

### Refund SPI

`RateLimiter` gains `default void refund(RateLimitKey key) {}`.
`Bucket4jRateLimiter` and `RedisRateLimiter` add one token back to the bucket the key resolves to, with Bucket4j's `addTokens`, which never fills a band above its capacity.
The in-memory limiter does nothing for a key whose bucket is not cached (an absent bucket is full).
The Redis limiter recreates an expired bucket full, so the refund changes nothing there either.

`notification.rate-limit.refund-unsent` (default `false`, `RateLimitProperties.isRefundUnsent()`) says whether the service should call `refund` for a request that was admitted but never sent.
`DefaultNotificationService` calls it, with the same `RateLimitKey` the send consumed from, when the idempotency release policy (DD-32) released the key of a failed send and the failure is neither `AMBIGUOUS` nor `UNKNOWN`.
With the default policy and `failure-policy=RELEASE_TRANSIENT` that is exactly a `TRANSIENT` failure.
A send without an idempotency key is never refunded: nothing tells the service that the caller will retry it.

### Bucket cache

The in-memory bucket cache stays at 10 000 buckets and one hour idle, as in DD-12; a shared scope reduces the number of buckets rather than adding to it.

## Reasoning

### Why a list of bands and not more fields

A list states any number of limits in one place, maps one-to-one to Bucket4j bands, and keeps the single-band fields meaningful.
Rejecting a rule that sets both avoids guessing which one the operator meant.

### Why scope on the rule and not on the key

The key is built by the service and is the SPI contract; changing it would break custom limiters and would need the service to know about rules.
Applying the scope after rule resolution keeps the precedence of DD-12 and DD-23 intact and lets each rule decide what it shares.

### Why the rule id is part of a shared bucket's key

Two rules can apply to the same channel for different requests, for example a shared `by-channel.sms` rule and a shared override for one tenant.
Keying the shared bucket by rule keeps their quotas apart and makes every bucket's shape come from exactly one rule.

### Why refund is a default method

Limiters written before 1.2.1 keep compiling and keep charging every admitted request, which is the 1.2.0 behaviour.

## Consequences

### Positive

- Multi-limit provider quotas and account-wide quotas can be expressed directly.
- In-memory and Redis limiters share one implementation of rule precedence.
- Default behaviour, the Redis key of existing buckets, and the `Retry-After` semantics are unchanged.

### Negative

- A tenant with its own override is not counted against a shared channel bucket, so the shared bucket alone does not cap the provider account when such overrides exist.
- `snapshot()` keys for shared buckets use the `*` tenant, which an admin UI has to recognise.
- With `refund-unsent` on, a caller that repeatedly retries under a key whose failures the release policy releases is no longer throttled by those failures.

## Related Decisions

- [12-rate-limiting.md](./12-rate-limiting.md) - the token-bucket model, key and precedence.
- [14-distributed-redis-backends.md](./14-distributed-redis-backends.md) - the Redis limiter.
- [23-per-channel-overrides.md](./23-per-channel-overrides.md) - per-channel rule defaults.
- [32-idempotency-fingerprint-failure-policy.md](./32-idempotency-fingerprint-failure-policy.md) - the release policy that triggers the refund.
