# Decision 21: Actuator Health Indicators

## Status: DECIDED

## Context

The service already pulls `spring-boot-starter-actuator` for the
`/actuator/health` endpoint, but no service-specific health indicators
are registered today. The default `/actuator/health` reports JVM-level
health and (when Redis is wired) Spring Data Redis's connection-level
health, neither of which tells operators what they actually need to
know:

- Is the DLQ approaching its bound? (A near-full DLQ is itself a
  signal something's wrong upstream.)
- Is the idempotency store wired up at all? (Misconfiguration where
  `enabled=true` but the bean failed to register would silently
  short-circuit the idempotency guarantee.)
- How many rate-limit buckets are active right now?
- Is the delivery-event store reachable and within bounds?

Each of these is an operator question that today requires hitting the
relevant admin endpoint manually. Surfacing them on
`/actuator/health/{component}` lets monitoring systems alert on the
boundary cases without polling six different URLs.

## Decision

One `HealthIndicator` per SPI, each registered conditionally on the
SPI bean being present. Indicators live in `notification-core` under
`com.lazydevs.notification.core.health`. Component names match the
SPI for natural URL shapes:

- `/actuator/health/dlq` — `DeadLetterStoreHealthIndicator`
- `/actuator/health/idempotency` — `IdempotencyStoreHealthIndicator`
- `/actuator/health/rateLimit` — `RateLimiterHealthIndicator`
- `/actuator/health/deliveryEvents` — `DeliveryEventStoreHealthIndicator`

(Audit doesn't get a dedicated indicator — its SPI surface is too thin
to probe meaningfully. The default audit backend is a no-op; a real
backend's health is already covered by the underlying persistence
infrastructure.)

### Per-indicator detail

Each indicator returns `Status.UP` (the SPI is wired and answering)
with a `details` map suited to the SPI:

```yaml
GET /actuator/health/dlq
{
  "status": "UP",
  "details": {
    "size": 47,
    "maxEntries": 1000,
    "fillPercent": 4
  }
}
```

When the configured bound is approached (default ≥ 80%), the
indicator reports `Status.OUT_OF_SERVICE` instead of `UP`. The DLQ
isn't *unhealthy* in the JVM sense — it's still answering — but
operators want their monitoring to alert on this, and
`OUT_OF_SERVICE` is the actuator-side signal for "drainable, not
broken." The threshold is configurable
(`notification.health.dlq-near-full-percent`, default 80).

`IdempotencyStoreHealthIndicator` and the audit absence have less to
probe — `UP` with `{ "enabled": true/false }` is enough.

### Indicator behaviour when the SPI is disabled

When `notification.dead-letter.enabled=false`, no
`DeadLetterStore` bean is registered and the indicator therefore
doesn't register either (`@ConditionalOnBean`). The
`/actuator/health/dlq` URL returns 404 — Spring Boot's standard
behaviour for unconfigured indicators. This is correct: an indicator
that's wired but always reports "feature disabled" is noise.

### Aggregation

Each indicator participates in the overall `/actuator/health`
aggregation per Boot's standard rules: any indicator reporting
`DOWN` short-circuits the overall status to `DOWN`;
`OUT_OF_SERVICE` short-circuits to `OUT_OF_SERVICE` (unless something
worse). This is the right default — operators with K8s liveness/readiness
probes on `/actuator/health` get correct signalling without extra
config.

Operators who want a different aggregation override
`management.endpoint.health.group.<name>.include` per Boot's standard
groups feature — not something we need to provide.

### Properties

```yaml
notification:
  health:
    dlq-near-full-percent: 80  # default; trigger OUT_OF_SERVICE at 80% of max-entries
```

That's the only knob. The rest of the indicators report static facts.

## Out of scope

- **Audit-store health.** No meaningful probe on the existing SPI;
  the underlying persistence backend (Mongo, JDBC) has its own
  Spring Data health indicator.
- **Provider connectivity probes.** "Is Twilio reachable?" is a
  question with its own infrastructure (their status page, our own
  retry telemetry). Probing in a health indicator would mean sending
  test traffic, which is its own design problem.
- **DLQ growth-rate alerting.** A monotonically-rising DLQ is bad
  even at 30% capacity. That's a metric question (DD-22), not a
  health-indicator question.
- **Per-tenant health.** Aggregated across tenants is the operational
  reality; per-tenant slicing belongs in metrics, not health.

## Reasoning

### Why `OUT_OF_SERVICE` rather than `DOWN` for a near-full DLQ

`DOWN` means "this thing is broken; don't route traffic to it" in
Boot's vocabulary. A near-full DLQ is not broken — it's *full of
information operators need to act on*. `OUT_OF_SERVICE` is the
correct nuance: "I'm running but you should drain me."

This also matters for liveness probes: a `DOWN` on the DLQ indicator
would, by default, fail the pod's liveness probe and trigger a
restart, which would not clear the DLQ. `OUT_OF_SERVICE` flips
readiness without restarting, which is the right blast-radius.

### Why one indicator per SPI rather than one composite

Composite indicators force operators to read JSON to figure out which
sub-component is unhealthy. Per-SPI indicators give them URLs they
can target individually with monitoring config. The aggregation at
`/actuator/health` still gives them the rolled-up view.

### Why no test-traffic probing

A health indicator that sends a real notification to verify
end-to-end works has two problems: it costs money (provider charges
on attempt) and it pollutes operator dashboards with synthetic
sends. Probing the *SPI surface* is the right granularity — broken
provider integrations show up in retry/DLQ counters (DD-22), not in
health.
