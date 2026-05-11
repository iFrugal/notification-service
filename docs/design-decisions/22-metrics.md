# Decision 22: Micrometer Metrics

## Status: DECIDED

## Context

The service is invisible to operator dashboards today. Send volume,
retry rate, DLQ depth, rate-limit denials, delivery-event arrival rate —
all of these are operationally important and none of them surface as
metrics. Operators can scrape `/actuator/health` (DD-21) for binary
state, but no time-series.

Spring Boot already pulls Micrometer in via
`spring-boot-starter-actuator`; the `notification-server` POM also
includes `micrometer-registry-prometheus` so the metrics endpoint is
ready to scrape. The only thing missing is the meter registration
itself.

## Decision

Add a `NotificationMetrics` helper class in `notification-core` that
wraps `MeterRegistry` and exposes typed increment methods for the
events that matter. Wire it as an
`Optional<NotificationMetrics>` into `DefaultNotificationService`
(and the other call sites) so the existing constructor wiring stays
back-compatible when Micrometer isn't present.

### Meter inventory

```
notification.sends.total{channel, status}            counter
notification.sends.duration{channel}                 timer
notification.retries.total{channel, attempt}         counter
notification.rate-limit.denied.total{channel}        counter
notification.idempotency.replay.total{tenant}        counter
notification.dlq.added.total{channel, failureType}   counter
notification.dlq.size                                gauge (read from DeadLetterStore.size)
notification.delivery-events.received.total{provider, status}   counter
notification.delivery-events.size                    gauge (read from DeliveryEventStore.size)
notification.webhook.signature.failed.total{provider}           counter
```

### Naming convention

`notification.<area>.<event>` — dotted, lowercase, plural-counted.
Matches Micrometer's recommended hierarchical naming. Each meter's
tag set is documented in the `NotificationMetrics` javadoc.

### Tag cardinality

The `tenant` tag is **only** present on
`notification.idempotency.replay.total` because that's the meter
where per-tenant slicing answers a real operational question
("which tenants are hitting the replay path most?"). Other meters
don't tag by tenant — high-cardinality tenant ids would explode
the time-series cardinality on registries like Prometheus.

`channel` and `status` are bounded enums (low cardinality, safe).
`attempt` is bounded by `notification.retry.max-attempts` (default
3, configurable).

Operators who want per-tenant breakdowns on other meters wire their
own tagging via a custom `NotificationMetrics` override.

### Wiring

The helper registers as a `@ConditionalOnClass(MeterRegistry.class)`
`@Component` so it's present when Micrometer is on the classpath
(it always is when `spring-boot-starter-actuator` is, which is the
default for `notification-server`). Other modules that pull
`notification-core` without actuator get an empty optional and the
service runs without metrics — no breakage.

The two gauges (`dlq.size`, `delivery-events.size`) register at
bean construction with a `Supplier<Number>` pointing at the
corresponding store's `size()` method. Micrometer scrapes them
on each registry-export poll, so the operator sees live values.

### Service-side touchpoints

Minimal surface change inside `DefaultNotificationService`:

```java
// success path
metrics.ifPresent(m -> m.recordSend(channel, response.status(), durationMillis));

// retry path inside RetryExecutor
metrics.ifPresent(m -> m.recordRetry(channel, attempt));

// rate-limit path inside the deny branch
metrics.ifPresent(m -> m.recordRateLimitDenied(channel));

// idempotency replay short-circuit
metrics.ifPresent(m -> m.recordIdempotencyReplay(tenantId));

// DLQ add inside the after-retries-exhausted path
metrics.ifPresent(m -> m.recordDlqAdded(channel, failureType));
```

Webhook controller adds the delivery-event counter on every
successful dispatch and the signature-failed counter on the 403
path.

### Disabling

Default behaviour is "register when Micrometer is on the classpath."
Operators who want to disable without removing the dep set
`management.metrics.enable.notification=false` per Boot's standard
metric-filtering. The service doesn't add its own kill switch — the
existing Boot config is enough.

## Out of scope

- **Per-provider send timer breakdown.** Useful but doubles the
  tag space (channel × provider). Easy to add later if operators
  want it.
- **Per-tenant counters across the board.** See "Tag cardinality" —
  high tenant counts would explode the registry.
- **Distributed tracing.** Spans / trace IDs are a separate
  concern; out of scope for this DD.
- **Pre-baked Grafana dashboards.** Useful but a different deliverable
  ("the metrics ship; the dashboard is documentation"). Operators
  build their own from the documented meter inventory.

## Reasoning

### Why a wrapper helper rather than inline `MeterRegistry`

Three reasons:

1. **Type safety.** `meterRegistry.counter("notification.sends.total",
   "channel", channel.name(), "status", status.name()).increment()`
   is error-prone; a typed `metrics.recordSend(channel, status,
   duration)` method gives the compiler something to check.
2. **Centralised naming.** All meter names live in one file — the
   javadoc on `NotificationMetrics` is the source of truth for the
   meter catalog. Rename a meter, change one place.
3. **Test isolation.** Tests inject a `SimpleMeterRegistry` once,
   then exercise the helper's methods. They never have to know the
   meter names directly.

### Why `Optional<NotificationMetrics>` rather than a no-op default

The no-op default would mean every send-path method body always calls
`metrics.recordSomething(...)` and the helper internally checks
"did the registry register?" — a per-call branch on the hot path.
`Optional<NotificationMetrics>.ifPresent(...)` reads naturally and
hot-path-skips one method call when metrics are disabled.

### Why no kill switch beyond Boot's standard mechanism

Adding `notification.metrics.enabled=false` would be a custom
property that does what Boot's
`management.metrics.enable.notification` already does. Two ways to
configure the same thing is worse than one — operators learn the
Boot-standard one and we don't carry the maintenance.

### Why a `Timer` for `send.duration` rather than a `LongTaskTimer`

`Timer` measures completed operations; `LongTaskTimer` measures
in-flight. Sends are short (provider call + audit) and completion-bound;
`Timer` is the right shape. `LongTaskTimer` is what you'd reach for if
sends could hang for minutes.
