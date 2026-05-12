# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **DD-23 Per-channel retry + rate-limit overrides** — new `byChannel`
  map on `RetryProperties` and `RateLimitProperties` lets operators
  set per-channel defaults (SMS tighter than email, etc.) without
  enumerating per-tenant overrides. Closes DD-12 §"Out of scope" +
  DD-13 §"Per-channel retry config". `RetryExecutor` gets a
  channel-aware `execute(Channel, Supplier)` overload; old signature
  retained for back-compat. Rate-limit precedence:
  most-specific-tuple override → `byChannel` default → global
  `defaultRule`. Backwards-compatible — top-level retry fields stay;
  `byChannel` composes on top.
- **DD-21 Actuator health indicators** — per-SPI status at
  `/actuator/health/{dlq,idempotency,rateLimit,deliveryEvents}`. DLQ
  flips to `OUT_OF_SERVICE` (not `DOWN`) at a configurable fullness
  threshold so K8s liveness probes don't restart-spam a near-full
  buffer. Property: `notification.health.dlq-near-full-percent`
  (default 80).
- **DD-22 Micrometer metrics** — typed `NotificationMetrics` helper
  wraps `MeterRegistry`. Ten meters across the send path: counters
  for sends, retries, rate-limit denials, idempotency replay, DLQ
  additions, delivery-event arrivals, webhook signature failures;
  gauges for DLQ size + delivery-event store size; one timer for
  send duration. Tag cardinality bounded — `tenant` only on
  `idempotency.replay`.
- **DD-20 Admin audit browse** — `GET /admin/audit/{requestId}` looks
  up a single audit record; `GET /admin/audit/recent?tenantId=…&limit=…`
  returns the most-recent N. New `findRecent` default method on
  `NotificationAuditService` returns `Optional.empty()` so existing
  audit impls compile unchanged.
- **DD-19 Bulk DLQ replay** — `POST /admin/dead-letter/replay-batch`
  re-submits many DLQ entries in one call. Mandatory `tenantId`
  (blast-radius safety), default `limit=100` capped at 1000,
  `?dryRun=true` for preview without side effects, per-entry results
  in the response body.
- **DD-18 Joined audit ↔ delivery query** — new `?requestId=…` filter
  on `GET /admin/delivery-events` walks
  `NotificationAuditService.findByRequestId` →
  `DeliveryEventStore.findByProviderMessageId`. New `auditState`
  field distinguishes `incomplete` (send still in flight) from
  `complete` (events may or may not have arrived).
- **DD-17 Persistent `DeliveryEventStore`** — bounded SPI with
  in-memory Caffeine default + Redis backend (`LPUSH`+`LTRIM`). Store
  extends `DeliveryEventListener` so a single bean satisfies both
  seams. New admin endpoint `GET /admin/delivery-events` with
  `?providerMessageId` filter and PII-safe attribute redaction by
  default.
- **DD-16 Webhook delivery callbacks** — `/webhooks/{provider}/...`
  surface for ingesting provider delivery callbacks. Twilio status
  (HMAC-SHA1) + SES via SNS (X.509). New `DeliveryStatus` enum +
  `DeliveryEvent` record + `DeliveryEventListener` SPI. FCM noted as
  not-supported (Firebase doesn't ship per-message webhooks).
- **DD-15 DLQ replay** — `POST /admin/dead-letter/{requestId}/replay`
  re-submits a dead-lettered notification with a fresh `requestId` +
  `idempotencyKey` and a server-set `replayOf` reference to the
  original. New SPI methods: `findByRequestId`, `remove`.
- **DD-14 Distributed Redis backends** — new `notification-redis`
  module providing Redis-backed implementations of the
  `IdempotencyStore`, `RateLimiter`, and `DeadLetterStore` SPIs.
  Spring Data Redis + Lettuce + bucket4j-redis. Each backend
  independently toggleable.
- **DD-13 Retries + DLQ** — opt-in synchronous retry with classified
  failures (`TRANSIENT` / `PERMANENT` / `UNKNOWN`) and exponential
  backoff with jitter. Pluggable `DeadLetterStore` SPI with bounded
  in-memory default. New `failureType` on `SendResult`,
  `RetryPredicate` SPI, `GET /admin/dead-letter` endpoint.
- **Phase 8 Provider classifiers** — `FailureTypes` helper +
  per-provider classifiers (SMTP, SES, Twilio) so retry decisions
  flow naturally from provider-specific errors.
- **DD-12 Rate limiting** — opt-in token-bucket throttle per
  `(tenantId, callerId, channel)` via Bucket4j. HTTP 429 +
  `Retry-After` on the REST path; drop-and-commit on Kafka. Admin
  endpoint at `/admin/rate-limit` exposes live bucket state.
- **DD-11 Caller identity** — optional `X-Service-Id` header maps to
  `callerId` on request/response/audit. Opt-in caller registry with
  `strict` rejection mode. `callerId` becomes part of the
  idempotency dedup tuple.
- **DD-10 Idempotency** — first-class `idempotencyKey` request field,
  scoped per `(tenantId, callerId, key)`. Caffeine default store with
  TTL + max-entries; `IdempotencyStore` SPI shaped for a Redis
  impl. New 409 status on in-flight duplicates. `X-Idempotent-Replay`
  response header marks cache hits.
- **OpenAPI / Swagger UI** — runtime schema at `/v3/api-docs` + UI at
  `/swagger-ui`. Springdoc 3.0.3 wired into `notification-rest`.

### Changed

- **Java 21 → Java 25 LTS, Spring Boot 3.2.2 → 4.0.5** —
  foundation upgrade. Includes Lombok 1.18.46 (JDK 25 support),
  Maven 3.9.9 wrapper, JaCoCo 0.8.14 (Java 25 bytecode = major
  version 69), `jakarta.annotation-api` declared explicitly (Boot 4
  dropped it as a transitive of `spring-context`).
- **`HealthIndicator` package** — Boot 4 moved it from
  `org.springframework.boot.actuate.health` (Boot 3) to
  `org.springframework.boot.health.contributor`. DD-21 uses the new
  package via `spring-boot-health`.
- **API records** — `NotificationResponse`, `Attachment`,
  `SendResult`, `RenderedContent`, and the `Recipient` hierarchy
  converted to sealed interfaces + records (Phase 1 refactor).
- **`SendResult.success(...)`** — added `failureType` component;
  factory methods default to `UNKNOWN` to keep the public API
  backwards-compatible.

### Fixed

- **PR #33 Redis bean wiring** — three layered bugs hidden by
  Docker-less `@Testcontainers(disabledWithoutDocker = true)` skips:
  inline test apps lacked `@EnableConfigurationProperties`,
  `RedisRateLimiter` eagerly opened a Lettuce connection in its
  constructor, and `@ConditionalOnMissingBean(SPI.class)` on a
  `@Component` was a Spring antipattern. Added a Docker-less
  `RedisBeansWiringTest` that asserts the four Redis beans register
  against a closed port — catches this class of regression before CI.
- **Boot 4 split jackson autoconfig** — removed Jackson 2 / Jackson 3
  conflict in `application.yml`; `CallerAdmissionFilter` builds its
  own `ObjectMapper` instead of injecting one (the bean isn't always
  available depending on which autoconfig path runs).
- **`@EnableConfigurationProperties` on `NotificationServerApplication`**
   — server doesn't depend on the starter, so it needs to bind
  `NotificationProperties` explicitly.

### Security

- **Webhook signature verification** (DD-16) — Twilio HMAC-SHA1 with
  constant-time comparison; SNS X.509 with hostname pinning to
  `*.amazonaws.com` (subdomain trick / look-alike domain / non-https
  rejected) + cached certificate via Caffeine. Failed verification
  returns `403`.
- **Log injection sanitization** (DD-12 / DD-13 round-2) — caller-
  supplied strings (`requestId`, provider name, etc.) stripped of
  ASCII control characters before being logged. Closes CodeQL
  findings.
- **`replayOf` server-set discipline** (DD-15) — REST controller and
  Kafka listener scrub client-submitted `replayOf` values to prevent
  spoofing the audit chain.

## [1.0.0] - 2026-04-23

### Added

- Initial Maven Central release of the multi-module notification
  service framework. Supports Email (SMTP, SES), SMS (Twilio, SNS),
  WhatsApp (Twilio, Meta), Push (FCM, APNs) via pluggable provider
  implementations.
- Multi-tenancy via `X-Tenant-Id` header (DD-03) with
  per-tenant channel + provider configuration.
- FreeMarker template engine wrapped via `persistence-utils` (DD-04).
- Two deployment modes (DD-08): library via Spring Boot Starter, or
  standalone Docker image with config-driven behaviour.
- REST API + Kafka consumer transports converging on the same
  `NotificationService.send()` core.
- Optional audit persistence via `persistence-api` (DD-07).
- CI/CD via GitHub Actions: build, release, deploy, Dependabot,
  CodeQL. SonarCloud quality gate.

[Unreleased]: https://github.com/iFrugal/notification-service/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/iFrugal/notification-service/releases/tag/v1.0.0
