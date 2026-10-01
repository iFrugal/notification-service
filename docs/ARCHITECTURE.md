# Architecture Overview

Notification Service is structured around one core service interface
and a set of optional SPIs that each solve one cross-cutting concern.
This document walks through the design as a coherent story for new
contributors. For the per-decision rationale, see
[`docs/design-decisions/`](./design-decisions/).

## Mental model: the send-path lifecycle

Every notification flows through the same pipeline. Each stage is
either core (always present) or opt-in (gated by config + an SPI
bean that registers conditionally):

```
                ┌─────────────────────────────┐
   request ──▶  │  enrichment                 │  core (DD-02/03/11)
                │  • requestId/tenantId       │
                │  • callerId from header     │
                └────────────┬────────────────┘
                             ▼
                ┌─────────────────────────────┐
                │  idempotency lookup         │  opt-in (DD-10)
                │  • IN_PROGRESS → 409        │
                │  • COMPLETE    → cached     │
                │  • FAILED      → fresh      │
                └────────────┬────────────────┘
                             ▼
                ┌─────────────────────────────┐
                │  rate-limit check           │  opt-in (DD-12)
                │  • token-bucket over        │
                │    (tenant, caller, channel)│
                │  • denied → 429 + Retry-After│
                └────────────┬────────────────┘
                             ▼
                ┌─────────────────────────────┐
                │  mark IN_PROGRESS           │  opt-in (DD-10)
                │  (atomic claim)             │
                └────────────┬────────────────┘
                             ▼
                ┌─────────────────────────────┐
                │  dispatch                   │  core
                │  • render template          │
                │  • resolve provider         │
                │  • provider.send()          │
                │    (wrapped in RetryExecutor│
                │     when DD-13 retry enabled)│
                └────────────┬────────────────┘
                             ▼
                ┌─────────────────────────────┐
                │  audit + idempotency close  │  core + DD-10
                │  • response cached against  │
                │    the idempotency key      │
                └────────────┬────────────────┘
                             │
              ┌──────────────┴───────────────┐
              ▼                              ▼
    SENT / DELIVERED                    FAILED / REJECTED
              │                              │
              │                              ▼
              │                ┌─────────────────────────────┐
              │                │  DLQ recording              │  opt-in (DD-13)
              │                │  + replay-batch (DD-19)     │
              │                │  + single replay (DD-15)    │
              │                └─────────────────────────────┘
              │
              ▼  (some time later — provider sends a status callback)
    ┌─────────────────────────────┐
    │  POST /webhooks/{provider}  │  opt-in (DD-16)
    │  • verify signature         │
    │  • parse                    │
    │  • dispatch to listeners    │
    │    (DeliveryEventStore is   │
    │     one such listener, DD-17)│
    └─────────────┬───────────────┘
                  ▼
    ┌─────────────────────────────┐
    │  query later                │  admin endpoints
    │  • /admin/audit/{id}        │  (DD-20)
    │  • /admin/delivery-events   │  (DD-17, DD-18)
    │  • /admin/dead-letter       │  (DD-13)
    └─────────────────────────────┘
```

## The SPI catalogue

Every cross-cutting concern is a Service Provider Interface in `notification-api` with a default implementation in `notification-core`.
The defaults are `@Bean` methods in `NotificationCoreDefaultsAutoConfiguration` guarded by `@ConditionalOnMissingBean`, so a bean you register yourself, or one from the Redis or JDBC module, takes their place.

| SPI | Module | Default impl | Redis backend (DD-14) | JDBC backend | Purpose |
|-----|--------|--------------|----------------------|--------------|---------|
| `IdempotencyStore` | `notification-api` | `CaffeineIdempotencyStore` | `RedisIdempotencyStore` | `JdbcIdempotencyStore` | Dedup against `(tenantId, callerId, idempotencyKey)` |
| `RateLimiter` | `notification-api` | `Bucket4jRateLimiter` | `RedisRateLimiter` | - | Token bucket over `(tenantId, callerId, channel)` |
| `DeadLetterStore` | `notification-api` | `InMemoryDeadLetterStore` | `RedisDeadLetterStore` | `JdbcDeadLetterStore` | Bounded buffer of retry-exhausted/permanent failures; `claim` / `release` lease entries for replay |
| `RetryPredicate` | `notification-api` | classifies by `FailureType` | - | - | Decides retry on `(SendResult, attempt)` |
| `DeliveryEventListener` | `notification-api` | `LoggingDeliveryEventListener` | - | - | Fan-out point for provider delivery callbacks |
| `DeliveryEventStore` | `notification-api` | `InMemoryDeliveryEventStore` | `RedisDeliveryEventStore` | `JdbcDeliveryEventStore` | Persistent store; **is also a listener** via default method |
| `NotificationAuditService` | `notification-core` | `NoOpAuditService` | - | - | Audit persistence; operators wire their own backend |

Dead-letter replay (DD-15, DD-19) claims entries before sending, acknowledges with `remove` and gives them back with `release`, under `notification.dead-letter.replay-lease`.
`JdbcDeadLetterStore` makes claims hold across replicas with `FOR UPDATE SKIP LOCKED`; the SPI's default `claim` methods, which the in-memory and Redis stores inherit, take no lease.

All SPIs except audit are opt-in via `notification.<feature>.enabled`.
Audit is built-in but defaults to no-op.

## Wiring: per-module auto-configuration

Nothing is component-scanned.
Each module lists its own auto-configuration in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, and the starter's `NotificationAutoConfiguration` registers no beans of its own:

| Module | Auto-configuration | Ordering |
|--------|--------------------|----------|
| `notification-core` | `NotificationCoreAutoConfiguration`, `NotificationCoreDefaultsAutoConfiguration`, `NotificationHealthAutoConfiguration`, `NotificationMetricsAutoConfiguration` | Health after the defaults and Redis; metrics after Boot's meter registry |
| `notification-redis` | `NotificationRedisAutoConfiguration` | After Boot's Redis, **before** the core defaults |
| `notification-store-jdbc` | `JdbcStoreAutoConfiguration` | After Boot's `DataSource`, **before** the core defaults |
| `notification-rest` | `NotificationRestAutoConfiguration` | **After** the core defaults and Redis, so its `@ConditionalOnMissingBean` on the logging delivery-event listener sees any store |
| `notification-kafka` | `NotificationKafkaAutoConfiguration` | Before Boot's Kafka |
| each provider module | `SmtpEmailProviderAutoConfiguration`, `SesEmailProviderAutoConfiguration`, `AcsEmailProviderAutoConfiguration`, `TwilioSmsProviderAutoConfiguration` | Unordered |

Running the Redis and JDBC auto-configurations before the core defaults is what lets their stores win: the in-memory default's `@ConditionalOnMissingBean` then sees the selected store and backs off.
REST registers nothing unless `notification.rest.enabled=true` in a servlet application, and restricts its tenant and caller filters to `<notification.rest.base-path>/*`.

**Store selection.**
`notification.store.type` (`memory`, `redis`, `jdbc`) picks the family per enabled feature, with `notification.redis.<feature>.enabled` as a per-feature override and the feature flag as the master switch (`StoreFeature`, `@ConditionalOnStoreType`).
`StoreTypeValidator`, a `BeanFactoryPostProcessor` registered by the core, fails startup before any bean is created when an enabled feature resolves to a family whose module is missing, naming the artifact.

**Providers.**
Each provider module registers its provider as a prototype bean named `<name><Channel>Provider` (for example `acsEmailProvider`).
`ProviderResolver` resolves a configured provider by `beanName`, then `fqcn`, then that conventional bean name.
`BuiltInProviders` is the catalog of known provider names, their classes and artifacts; it loads no class and serves to explain a miss (module absent, or provider not implemented yet) and to feed the GraalVM hints in `ProviderRuntimeHints`.
`ProviderFqcnAotProcessor` adds the same hints at AOT build time for every class the configuration names with `fqcn`.
A configured provider that cannot be resolved fails startup in `ProviderRegistry`.

## Transport surfaces

Three ways to submit a notification, all of which converge on the
same `NotificationService.send()`:

| Transport | Module | Module config | Notes |
|-----------|--------|---------------|-------|
| REST | `notification-rest` | `notification.rest.enabled` (default false since 1.1.0) | OpenAPI/Swagger via springdoc, optional in library mode (DD Phase 9) |
| Kafka | `notification-kafka` | `notification.kafka.enabled` (default false) | Honours `X-Tenant-Id` + `X-Service-Id` headers |
| Programmatic | `notification-api` | always available | Inject `NotificationService` directly |

The REST and Kafka transports both honour the same header conventions:

- `X-Tenant-Id` (DD-03) — required for multi-tenant routing
- `X-Service-Id` (DD-11) — optional caller identity; feeds idempotency
  + audit + caller registry

On REST, header names are matched case-insensitively: `TenantFilter` replaces the case-sensitive header map that `BasicRequestFilter` builds with a case-insensitive copy, and `CallerAdmissionFilter` falls back to `HttpServletRequest.getHeader`.

## Observability surfaces

Per DD-21 + DD-22 (Phase 16), the service exposes:

- `/actuator/health/{dlq,idempotency,rateLimit,deliveryEvents}` —
  per-SPI status. DLQ flips to `OUT_OF_SERVICE` (not `DOWN`) at
  configurable fullness threshold so K8s probes don't restart-spam.
- `/actuator/metrics/notification.*` — counters across the send path
  (sends, retries, rate-limit denied, idempotency replay, DLQ added,
  webhook events, signature failures), gauges (DLQ size, delivery
  events size), one timer (send duration). Bounded tag cardinality.

## Admin surface

Operator-facing endpoints under `/api/v1/admin/`:

| Endpoint | What it answers | DD |
|----------|----------------|----|
| `/configuration` | "What does my tenant/channel/provider config look like?" | DD-08 |
| `/caller-registry` | "Which callers are registered, and is strict mode on?" | DD-11 |
| `/rate-limit` | "What are the configured rules and live bucket state?" | DD-12 |
| `/dead-letter` | "What failed terminally?" | DD-13 |
| `/dead-letter/{id}/replay` | "Replay this specific entry" | DD-15 |
| `/dead-letter/replay-batch` | "Replay every entry for tenant X" | DD-19 |
| `/delivery-events` | "What provider callbacks have arrived?" | DD-17 |
| `/delivery-events?requestId=X` | "Did request X get delivered?" | DD-18 |
| `/audit/{requestId}` | "What's the audit row for X?" | DD-20 |
| `/audit/recent` | "What just happened on tenant X?" | DD-20 |

`/admin/*` is intended for operator tooling and is expected to be
firewalled or authenticated at the deployment layer (DD-08 §"Admin
surface" — the service itself does not ship admin auth).

## Module layout

```
notification-api                  SPIs, DTOs, exceptions; no logic
notification-core                 Default impls, business logic, autoconfig
notification-rest                 REST controllers, filters, webhook surface
notification-kafka                Kafka consumer
notification-redis                Redis-backed SPI implementations (DD-14)
notification-store-jdbc           PostgreSQL-backed stores over plain SQL
notification-channels/*           Channel + provider implementations
  notification-channel-email
    email-provider-smtp
    email-provider-ses
    email-provider-acs
  notification-channel-sms
    sms-provider-twilio
notification-spring-boot-starter  The library-mode dependency; registers no beans itself
notification-server               Standalone Docker app
```

Total: 16 modules, including the root and aggregator poms.
The strict separation lets consumers pull only the providers they use, so an SMTP-only deployment doesn't transitively inherit AWS or Twilio SDKs.
AWS SNS SMS, WhatsApp (Twilio, Meta) and push (FCM, APNs) providers are planned; until they ship, the `SmsProvider`, `WhatsAppProvider` and `PushProvider` SPIs in `notification-api` are the extension points.
Audit has no module of its own: the `NotificationAuditService` SPI and its `NoOpAuditService` default live in `notification-core`.

## Cross-cutting invariants

Three invariants the send-path machinery maintains, all hard-won
in DD-13 (Retries + DLQ):

1. **One rate-limit token per logical send**, not per attempt.
   Retrying internally must not double-charge the bucket.
2. **One idempotency claim per logical send**, not per attempt.
   The same request being replayed by the retry loop is *still*
   one request from the operator's perspective.
3. **DLQ entry records the request as the operator sent it**, not the
   final-attempt rendered form. Replay (DD-15) needs the original
   payload to re-run through the full pipeline.

## Server-set request fields

Some `NotificationRequest` fields are computed by the service and
must not be trusted from clients:

- `replayOf` (DD-15) — set only by `POST /admin/dead-letter/{id}/replay`;
  REST controller + Kafka listener scrub client-submitted values.
- `callerId` (DD-11) — falls back to `X-Service-Id` header when the
  body doesn't set it. Body wins per `tenantId` precedence (DD-03).

## Versioning and release

The project follows semantic versioning. The current snapshot is
`1.0.3-SNAPSHOT`; `1.0.2` is the most-recent released version on
Maven Central, and the next release is `1.1.0`.
Release automation is wired via GitHub Actions
(see `.github/workflows/release.yml`). Maven Central publishing
goes through the Central Portal via
`central-publishing-maven-plugin`. GPG signing on the
release artifacts is done by `crazy-max/ghaction-import-gpg`.

## Where to go next

- **Integrating the service?** → start at the
  [`docs/FEATURE_MATRIX.md`](./FEATURE_MATRIX.md) — every feature,
  which JAR to add, which property to flip, three worked examples.
- **Bug or feature work** → start at
  [`docs/PROGRESS.md`](./PROGRESS.md) (the live tracker of in-flight
  and queued work) and the relevant
  [`docs/design-decisions/NN-*.md`](./design-decisions/) for context.
- **Add a new SPI** → follow the pattern: interface in `notification-api`, default `@Bean` with `@ConditionalOnProperty` and `@ConditionalOnMissingBean` in `NotificationCoreDefaultsAutoConfiguration`, optionally Redis and JDBC impls guarded by `@ConditionalOnStoreType` in their modules' auto-configurations, and a property block on `NotificationProperties`.
- **Add a new provider** → see "Adding Custom Providers" in the [README](../README.md).
  A built-in provider module ships an auto-configuration that registers a prototype bean named `<name><Channel>Provider` under `@ConditionalOnMissingBean(name = ...)`, so an application bean of the same name replaces it, plus an entry in `BuiltInProviders`.
  A custom provider needs no auto-configuration: a prototype bean selected by `beanName` (or named by the same convention), or a class named by `fqcn`.
  The choice between Spring bean and FQCN reflection is documented in DD-05 / DD-06.
- **Add a new channel** → new `notification-channel-{name}` module
  plus at least one provider impl. Channels are an enum in
  `notification-api` so a new channel requires an api-module edit.

## Reading order for the design-decisions corpus

If you're trying to understand the whole system, the DDs are best
read roughly in the order they shipped, since later ones build on
earlier ones:

1. **DD-01 through DD-08** — bedrock: module layout, multi-tenancy,
   audit, packaging.
2. **DD-10** — first-class idempotency. Establishes the
   "SPI with conditional default impl" pattern every later DD reuses.
3. **DD-11** — caller identity. Adds the third dimension to dedup +
   audit.
4. **DD-12 / DD-13** — rate-limit, retries, DLQ. Together they
   establish the cost-control story.
5. **DD-14** — the distributed-state story (Redis). All earlier SPIs
   get a multi-pod backend.
6. **DD-15** — DLQ replay. First operator-driven recovery flow.
7. **DD-16 / DD-17** — webhook ingestion + persistent event store.
   Closes the "sent ≠ delivered" visibility gap.
8. **DD-18** — joined audit ↔ delivery query. End-to-end
   visibility for support.
9. **DD-19 / DD-20** — operator-surface bundle: bulk replay + audit
   browse.
10. **DD-21 / DD-22** — observability bundle: actuator health +
    Micrometer metrics.

Reading them in order makes the cumulative design coherent. Reading
them out of order is fine for targeted work — each DD's "Context"
section restates the relevant background.
