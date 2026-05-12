# notification-service — Progress Tracker

A single source of truth for what's done, what's in flight, and what's
queued, persisted in the repo so any new chat session (or any human
collaborator) can pick up where the last one left off.

**Conventions**

- Update this file at the end of every meaningful action (PR raised, PR
  merged, design decision flipped, blocker discovered).
- Use `[x]` for done, `[~]` for in flight (with PR link), `[ ]` for queued.
- "Last updated" gets bumped whenever any row changes.
- The "Open PRs" and "Recently merged" sections are the live state — they
  must reflect the actual GitHub state, not intent.

---

## Status snapshot

- **Repository:** `iFrugal/notification-service`
- **Current version on Maven Central:** `1.0.0`
- **Working version (snapshot):** `1.0.1-SNAPSHOT`
- **Java:** 25 LTS · **Spring Boot:** 4.0.5 · **Build:** Maven 3.9.9 (`./mvnw`)
- **CI/CD:** GitHub Actions (build, release, deploy, dependabot, codeql)
- **Quality gate:** SonarCloud (`iFrugal_notification-service`)
- **Last updated:** 2026-05-12 IST (DD-19/20/21/22 + docs all merged; DD-23 per-channel-overrides PR open; 1.0.1 release pending).
  All dates in this file are local IST (UTC+5:30) since that's where
  the work is happening; UTC equivalents differ by ~5h30m.

---

## Phases

### Phase 0 — Bootstrap

- [x] Maven wrapper (`./mvnw`) generated, Java 25 toolchain pinned
- [x] Multi-module skeleton — api / core / channels / audit / kafka / rest /
      starter / server
- [x] Parent POM on Spring Boot 4.0.5, JaCoCo 0.8.14 override (Java 25
      bytecode = major version 69)
- [x] First green build, first PR merged

### Phase 0.5 — Platform & CI/CD

- [x] GitHub Actions: build / release / deploy / dependabot / codeql
      (modeled on `iFrugal/all-about-persistence`)
- [x] Maven Central publishing via Central Portal
      (`central-publishing-maven-plugin`)
- [x] GPG signing via `crazy-max/ghaction-import-gpg@v6`
- [x] `1.0.0` released to Maven Central
- [x] SonarCloud integration + README badges

### Phase 1 — Core API & Domain (DD-01 through DD-08)

- [x] DD-01 Maven module structure
- [x] DD-02 Notification request structure (records where possible)
- [x] DD-03 Multi-tenancy (`X-Tenant-Id` header, `TenantContext`
      ThreadLocal, default tenant)
- [x] DD-04 Template engine (FreeMarker via `persistence-utils`)
- [x] DD-05 Channel & provider registration (`beanName` + `fqcn` hybrid,
      fail-fast)
- [x] DD-06 External provider discovery (no SPI, lifecycle hooks)
- [x] DD-07 Audit persistence (pluggable via `persistence-api`)
- [x] DD-08 Packaging modes (single Docker image, config-driven, masked
      configuration API)

### Phase 2 — Test framework migration

- [x] TestNG → JUnit 5 + AssertJ + Mockito + Awaitility

### Phase 3 — Idempotency (DD-10)

- [x] DD-10 design doc
- [x] SPI (`IdempotencyStore`, `IdempotencyKey`, `IdempotencyRecord`,
      `IdempotencyStatus`) in `notification-api`
- [x] Caffeine default impl in `notification-core` (auto-config, TTL,
      max-entries, async eviction)
- [x] Service integration in `DefaultNotificationService` —
      check / mark-in-progress / replay / mark-complete in finally
- [x] 409 controller advice for `IdempotencyInProgressException`
- [x] `X-Idempotent-Replay` HTTP header (DD-10 §REST-API-behaviour)
- [x] JUnit 5 tests across SPI, service, and controller layers (19 tests)
- [x] README REST API section + scope examples

### Phase 4 — Caller Identity (DD-11) ✅

- [x] Single PR rolling everything up — merged as
      [#26](https://github.com/iFrugal/notification-service/pull/26)
  - [x] DD-11 design doc + decision-log entry
  - [x] `callerId` on `NotificationRequest` (Lombok), `NotificationResponse`
        (record, 14th component), `NotificationAudit`
  - [x] `CallerRegistry` + `CallerRegistryProperties` (off by default,
        `strict` flag for opt-in 403)
  - [x] `TenantFilter` reads `X-Service-Id` → `RequestContext`;
        `CallerAdmissionFilter` enforces strict mode
  - [x] `DefaultNotificationService.idempotencyKeyFor()` consumes
        `request.getCallerId()` instead of hardcoded `null`; enrichment
        pulls header value from `RequestContext`
  - [x] `AdminController` exposes `/admin/caller-registry`
  - [x] Tests — `CallerRegistryTest` (7), `TenantFilterTest` (4),
        `CallerAdmissionFilterTest` (4), idempotency-with-callerId case
        added to existing `DefaultNotificationServiceIdempotencyTest`
  - [x] README — "Caller Identity" section + Features bullet +
        admin-endpoints row

### Phase 5 — Kafka picks up `X-Service-Id` ✅

- [x] Single PR — extended DD-11 to the Kafka transport, merged as
      [#28](https://github.com/iFrugal/notification-service/pull/28)
  - [x] `NotificationKafkaListener` reads `X-Service-Id` header,
        stamps onto `request.callerId` (body wins per DD-11)
  - [x] `NotificationKafkaListenerTest` — 6 tests
  - [x] README — Kafka section explains both headers + admission semantics

### Phase 6 — Rate limiting (DD-12) ✅

- [x] Single PR — token-bucket throttle per `(tenant, caller, channel)`,
      merged as [#29](https://github.com/iFrugal/notification-service/pull/29)
  - [x] DD-12 design doc + decision-log entry
  - [x] `RateLimiter` SPI + Bucket4j default impl + properties
  - [x] Wired into `DefaultNotificationService.send()`
  - [x] REST 429 + `Retry-After`, admin endpoint
  - [x] Round-2 review fixes (sanitize-at-construction, Caffeine bucket
        cache, `@Validated` config, deprecated-API migration)

### Phase 7 — Retries + DLQ (DD-13) ✅

- [x] Single PR — merged as
      [#30](https://github.com/iFrugal/notification-service/pull/30)
  - [x] DD-13 design doc + SPI + Bucket4j-style opt-in
  - [x] `FailureType`, `SendResult.failureType`, `RetryPredicate` SPI
  - [x] `RetryExecutor` (exponential backoff + jitter, no
        Resilience4j dep)
  - [x] `DeadLetterStore` SPI + `InMemoryDeadLetterStore`
  - [x] Service integration; one rate-limit token / one idempotency
        lock per logical send (not per attempt)
  - [x] `GET /admin/dead-letter` (PII-safe)
  - [x] Round-2 review fixes: log injection sanitization, validation
        tightening, contextual-keyword rename, `Math.clamp`, etc.

### Phase 8 — Provider FailureType classification ✅

- [x] Single PR — merged as
      [#31](https://github.com/iFrugal/notification-service/pull/31)
  - [x] `FailureTypes` shared helper + per-provider classifiers
        (SMTP / SES / Twilio); 34 unit tests
  - [x] README per-provider TRANSIENT/PERMANENT table

### Phase 9 — OpenAPI / Swagger generation ✅ (CI artifact upload still pending)

- [~] Single PR — runtime schema + CI artifact
  - [x] springdoc 3.0.3 (matches Spring Boot 4.0.5) wired into
        `notification-rest`
  - [x] `OpenApiConfig` — service-level metadata (title, description
        documenting the four cross-cutting headers and DD-specific
        status codes), reusable header parameter / response schemas
  - [x] `@Tag` on `NotificationController` and `AdminController`;
        `@Operation` + `@ApiResponse` on the headline endpoints
        (`/notifications`, `/notifications/batch`, `/notifications/async`,
        `/admin/caller-registry`, `/admin/rate-limit`,
        `/admin/dead-letter`)
  - [x] `OpenApiSmokeTest` — boots full Spring context, hits
        `/v3/api-docs`, asserts schema validity + key paths, persists
        `target/openapi.json` for CI to upload
  - [~] CI workflow upload of the persisted schema as the
        `openapi-schema` build artifact — change is staged locally but
        the PAT used in this sandbox lacks `workflow` scope, so the
        `.github/workflows/ci.yml` edit needs a separate PR raised
        from a workflow-enabled token (or manually committed via the
        GitHub UI). The smoke test still produces
        `notification-server/target/openapi.json` on every build,
        ready for whichever upload step lands
  - [x] Boot-4 housekeeping found along the way:
        `@EnableConfigurationProperties` on `NotificationServerApplication`
        (server doesn't depend on the starter), removed Jackson 2 /
        Jackson 3 conflict in `application.yml` (Boot 4's split jackson
        autoconfig binds against Jackson 3 enum types),
        `CallerAdmissionFilter` builds its own `ObjectMapper` instead
        of injecting one (the bean isn't always available depending on
        which jackson-autoconfig path runs)
  - [x] README — Live API documentation subsection, Features bullet
  - [x] PR raised: [#32](https://github.com/iFrugal/notification-service/pull/32)

### Phase 10 — Distributed Redis backends (DD-14) ✅

- [x] Single PR — new `notification-redis` module with Redis-backed
      implementations of all three SPIs, merged as
      [#33](https://github.com/iFrugal/notification-service/pull/33)
  - [x] DD-14 design doc + decision-log entry
  - [x] `notification-redis` module (Spring Data Redis + Lettuce +
        bucket4j-redis); `RedisProperties` nested config in
        `NotificationProperties`; per-feature toggles + master switch
  - [x] `RedisIdempotencyStore` — `SET NX EX` atomic claim,
        JSON-serialised `IdempotencyRecord`, TTL via Redis `EX`
  - [x] `RedisRateLimiter` — bucket4j-redis Lettuce ProxyManager,
        lazy-init connection (defers Lettuce TCP open to first
        `tryConsume`), same override-resolution logic as the
        in-memory limiter
  - [x] `RedisDeadLetterStore` — `LPUSH` + `LTRIM` for bounded
        recent-N list, JSON-serialised `DeadLetterEntry` (operators
        can `redis-cli LRANGE` to debug)
  - [x] All three impls gated by `@ConditionalOnProperty` for
        explicit opt-in. Initial wiring used
        `@ConditionalOnMissingBean(SPI.class)` on the `@Component`
        but that's a Spring antipattern (condition self-filters);
        dropped — operators register a custom impl by leaving the
        Redis flag `false`
  - [x] Testcontainers integration tests (13 cases across 3 classes)
        — `@Testcontainers(disabledWithoutDocker = true)` so they
        skip cleanly on Docker-less hosts; CI has Docker
  - [x] `RedisBeansWiringTest` — Docker-less Spring context smoke
        test against a closed port; catches bean-wiring regressions
        even when Testcontainers all skip
  - [x] README — "Distributed deployment" section + Features bullet

### Phase 11 — DLQ replay (DD-15) ✅

- [x] Single PR — operator-driven replay endpoint that closes the loop
      DD-13 §"Out of scope" promised, merged as
      [#35](https://github.com/iFrugal/notification-service/pull/35)
  - [x] DD-15 design doc + decision-log entry
  - [x] `DeadLetterStore` SPI extended with `findByRequestId(tenantId,
        requestId)` + `remove(tenantId, requestId)` — default no-op
        impls so existing custom backends compile unchanged
  - [x] Both default impls (`InMemoryDeadLetterStore`,
        `RedisDeadLetterStore`) implement the new methods. Redis uses
        `LRANGE` + `LREM` against the bounded list; in-memory walks the
        Caffeine map (replay is operator-driven, not a hot path)
  - [x] `replayOf` field on `NotificationRequest` and
        `NotificationAudit` — server-set only; REST controller and
        Kafka listener scrub client-submitted values with a WARN
  - [x] `POST /admin/dead-letter/{requestId}/replay` —
        builds a fresh request from the captured payload (new
        `requestId` + `replay-` idempotency key, `replayOf` set), calls
        `NotificationService.send()`, removes the entry on success.
        404 on unknown id; 502 when the replay reaches a provider but
        fails again; 503 when the DLQ is disabled
  - [x] Tests — `AdminControllerReplayTest` (6),
        `NotificationControllerReplayOfScrubbingTest` (2),
        `InMemoryDeadLetterStoreTest` extended (6 new cases)
  - [x] README — replay subsection under DD-13 + admin-endpoints
        row + Features bullet update

### Phase 12 — Webhook delivery callbacks (DD-16) ✅

- [x] Single PR — opt-in `/webhooks/{provider}/...` ingestion of
      provider delivery callbacks; closed the "sent ≠ delivered"
      visibility gap, merged as
      [#38](https://github.com/iFrugal/notification-service/pull/38)
  - [x] DD-16 design doc + decision-log entry
  - [x] `DeliveryStatus` enum (DELIVERED / BOUNCED / COMPLAINED /
        FAILED_AT_PROVIDER / UNKNOWN) and `DeliveryEvent` record on
        `notification-api`
  - [x] `DeliveryEventListener` SPI; default `LoggingDeliveryEventListener`
        registered when no other listener is on the classpath
  - [x] `WebhookProperties` nested config (master switch +
        per-provider toggles + signature-verification flags)
  - [x] `TwilioSignatureVerifier` — HMAC-SHA1 over URL + sorted form
        params, constant-time comparison
  - [x] `SnsSignatureVerifier` — stdlib X.509 (SHA1withRSA + SHA256withRSA),
        cert URL host pinned to `*.amazonaws.com`, signing-cert cached
        with Caffeine
  - [x] `WebhookController` — `POST /webhooks/twilio/status` (form),
        `POST /webhooks/ses/sns` (JSON envelope, handles
        SubscriptionConfirmation by logging the SubscribeURL for
        operator confirmation)
  - [x] Tests — `WebhookControllerTest` (16),
        `TwilioSignatureVerifierTest` (9),
        `SnsSignatureVerifierTest` (6)
  - [x] README — "Webhook delivery callbacks" section + Features bullet

### Phase 13 — Persistent DeliveryEventStore (DD-17) ✅

- [x] Single PR — bounded `DeliveryEventStore` SPI so operators get
      `GET /admin/delivery-events` without writing a custom listener,
      merged as [#40](https://github.com/iFrugal/notification-service/pull/40)
  - [x] DD-17 design doc + decision-log entry
  - [x] `DeliveryEventStore` SPI on `notification-api`. Extends
        `DeliveryEventListener` (default `onEvent` calls `add`), so
        registering a store bean automatically joins the listener
        fan-out the webhook controller already iterates — no
        autoconfig glue
  - [x] `InMemoryDeliveryEventStore` — Caffeine bounded LRU, mirrors
        the DD-13 `InMemoryDeadLetterStore` shape
  - [x] `RedisDeliveryEventStore` — same LPUSH+LTRIM pattern as
        `RedisDeadLetterStore`, gated on
        `notification.redis.delivery-events.enabled=true`
  - [x] `DeliveryEventProperties` (max-entries 5_000 default; higher
        than DLQ because callbacks arrive proportional to send volume)
        + Redis nested toggle
  - [x] `GET /admin/delivery-events` — snapshot + size + optional
        `?providerName` + `?providerMessageId` filter. Raw provider
        attributes excluded by default (PII redaction matches DD-13);
        opt in with `?includeRaw=true`
  - [x] `RedisBeansWiringTest` extended to assert the new bean
        registers
  - [x] Tests — `InMemoryDeliveryEventStoreTest` (8),
        `AdminControllerDeliveryEventsTest` (7),
        `RedisDeliveryEventStoreIntegrationTest` (5)
  - [x] README — "Persisted delivery events" subsection under DD-16
        + admin-endpoints row + Features bullet + Redis YAML example

### Phase 14 — Joined audit ↔ delivery query (DD-18) ✅

- [x] Single PR — `?requestId=…` filter on `/admin/delivery-events`
      walks `NotificationAuditService.findByRequestId` then
      `DeliveryEventStore.findByProviderMessageId`, merged as
      [#41](https://github.com/iFrugal/notification-service/pull/41).
      Composition of DD-15 + DD-17, no new SPI
  - [x] DD-18 design doc + decision-log entry
  - [x] `AdminController.getDeliveryEvents` extended — `?requestId`
        takes precedence over `?providerName + ?providerMessageId`
        which takes precedence over snapshot
  - [x] `auditState` field distinguishes `incomplete` (send still in
        flight) from `complete` (send done, events may or may not
        have arrived). 404 when audit record absent (covers the
        no-op audit case operationally)
  - [x] Tests — `AdminControllerDeliveryEventsByRequestIdTest` (6):
        audit not found / audit incomplete / audit complete no events /
        audit complete with events / precedence over provider tuple /
        blank requestId falls back to snapshot
  - [x] Existing test constructor sites updated for new
        `NotificationAuditService` arg (`AdminControllerReplayTest`,
        `AdminControllerDeliveryEventsTest`)
  - [x] README — "Did this notification deliver?" prose under the
        existing delivery-events section + Features bullet update

### Phase 15 — Operator-surface bundle (DD-19 + DD-20) ✅ (merged as #42)

- [~] Bundled PR — two coherent operator-facing surfaces ship
      together because both are low-risk extensions of existing
      admin endpoints with no SPI changes beyond a single default
      method
  - [x] DD-19 design doc + decision-log entry
  - [x] DD-20 design doc + decision-log entry
  - [x] `NotificationAuditService.findRecent(tenantId, limit)`
        default method on the SPI — returns `Optional.empty()` so
        existing impls compile (`NoOpAuditService` returns empty;
        backends that can iterate override)
  - [x] `POST /admin/dead-letter/replay-batch?tenantId=…&dryRun=…&limit=…`
        — mandatory tenantId (blast-radius), default limit 100
        capped at 1000, dryRun returns preview without side
        effects, live mode returns per-entry results
  - [x] `GET /admin/audit/{requestId}` — single-record lookup, 404
        on miss (covers the `NoOpAuditService` overload like DD-18
        does)
  - [x] `GET /admin/audit/recent?tenantId=…&limit=…` — most-recent
        listing, 200 + `entries: null` + message when the SPI
        returned `Optional.empty()`
  - [x] Tests — `AdminControllerBulkReplayTest` (8),
        `AdminControllerAuditBrowseTest` (8). All 35 admin-controller
        tests green (incl. existing DD-15, 17, 18)
  - [x] README — admin-endpoints table rows for all three new
        endpoints

### Phase 15 — Operator-surface bundle (DD-19 + DD-20) ✅

- [x] Bundled PR — `POST /admin/dead-letter/replay-batch` + audit
      browse (`GET /admin/audit/{id}` + `/recent`), merged as
      [#42](https://github.com/iFrugal/notification-service/pull/42)

### Phase 16 — Observability bundle (DD-21 + DD-22) ✅

- [x] Bundled PR — per-SPI actuator health indicators + Micrometer
      metrics across the send path, merged as
      [#43](https://github.com/iFrugal/notification-service/pull/43).
      Four `HealthIndicator` beans (DLQ / idempotency / rate-limit /
      delivery-events) each `@ConditionalOnClass(HealthIndicator)`
      + `@ConditionalOnProperty`. DLQ flips to `OUT_OF_SERVICE` at
      configurable threshold (default 80%). `NotificationMetrics`
      helper wraps `MeterRegistry` — 8 counters + 2 gauges + 1 timer;
      `tenant` tag bounded to `idempotency.replay`. 20 new tests.

### Phase 17 — Architecture + changelog docs ✅

- [x] `docs/ARCHITECTURE.md` walking the system as a coherent
      narrative; `CHANGELOG.md` in Keep-a-Changelog format; README
      cross-links. Merged as
      [#44](https://github.com/iFrugal/notification-service/pull/44)

### Phase 18 — Per-channel retry + rate-limit overrides (DD-23) ← in flight

- [~] Single PR — closes DD-12 §"Out of scope" + DD-13's promised
      per-channel tunables. Backwards-compatible: top-level retry
      fields stay; new `byChannel` slot composes on top
  - [x] DD-23 design doc + decision-log entry
  - [x] `RetryProperties.byChannel: Map<String, RetryRule>` +
        `ruleFor(channel)` helper; new `RetryRule` value type with
        the same validation as the top-level fields
  - [x] `RetryExecutor.execute(Channel, Supplier)` overload (old
        signature retained for back-compat). `computeBackoff` now
        takes the resolved rule
  - [x] `RateLimitProperties.byChannel: Map<String, RateLimitRule>`
        — sits between per-tuple overrides and the global default in
        precedence (per-tuple > byChannel > defaultRule)
  - [x] `Bucket4jRateLimiter` + `RedisRateLimiter` consult byChannel
        after exhausting per-tuple overrides
  - [x] `DefaultNotificationService` passes `request.getChannel()`
        to the executor
  - [x] Tests — `RetryExecutorTest` extended with 5 byChannel cases,
        `Bucket4jRateLimiterTest` extended with 4 byChannel cases

### Phase 19 — Cut 1.0.1 release ← next

- [ ] Bump `1.0.1-SNAPSHOT` → `1.0.1` in all module POMs, flip
      `CHANGELOG.md` `[Unreleased]` → `[1.0.1]`, tag, push, let
      CI publish to Maven Central, bump to `1.0.2-SNAPSHOT`.

### Out-of-scope / parked

- Jackson 2 → Jackson 3 migration (cleanup; Boot 4 defaults to
  Jackson 3 but we pin Jackson 2 in the parent POM). Risky; deferred
  until forced.
- CI workflow upload of `openapi.json` (12-line edit blocked on PAT
  workflow scope). User to commit when convenient.
- FCM delivery callbacks (Firebase doesn't ship per-message webhooks
  today; revisit if pull-based BigQuery export is in scope).

---

## Open PRs

| # | Title | Branch | Status | Notes |
|---|-------|--------|--------|-------|
| (pending) | feat(dd-23): per-channel retry + rate-limit overrides | `feat/dd-23-per-channel-overrides` | **awaiting CI/review** | Phase 18 — final feature PR for the v1 cycle |

---

## Recently merged

| PR | Title | Merged |
|----|-------|--------|
| [#44](https://github.com/iFrugal/notification-service/pull/44) | docs: add ARCHITECTURE.md + CHANGELOG.md | 2026-05-11 |
| [#43](https://github.com/iFrugal/notification-service/pull/43) | feat(dd-21+dd-22): actuator health indicators + Micrometer metrics | 2026-05-11 |
| [#42](https://github.com/iFrugal/notification-service/pull/42) | feat(dd-19+dd-20): bulk DLQ replay + admin audit browse | 2026-05-11 |
| [#41](https://github.com/iFrugal/notification-service/pull/41) | feat(dd-18): GET /admin/delivery-events?requestId — audit↔delivery join | 2026-05-11 |
| [#40](https://github.com/iFrugal/notification-service/pull/40) | feat(dd-17): persistent DeliveryEventStore + GET /admin/delivery-events | 2026-05-11 |
| [#38](https://github.com/iFrugal/notification-service/pull/38) | feat(dd-16): webhook delivery callbacks (Twilio + SES via SNS) | 2026-05-11 |
| [#35](https://github.com/iFrugal/notification-service/pull/35) | feat(dd-15): DLQ replay endpoint with replayOf chain | 2026-05-06 |
| [#34](https://github.com/iFrugal/notification-service/pull/34) | docs(progress): mark Phase 10 / DD-14 done after PR #33 merge | 2026-04-30 |
| [#33](https://github.com/iFrugal/notification-service/pull/33) | feat(dd-14): distributed Redis backends | 2026-04-30 |
| [#32](https://github.com/iFrugal/notification-service/pull/32) | feat(openapi): springdoc 3 schema + Swagger UI | 2026-04-30 |
| [#31](https://github.com/iFrugal/notification-service/pull/31) | feat(channels): provider FailureType classification | 2026-04-30 |
| [#30](https://github.com/iFrugal/notification-service/pull/30) | feat(dd-13): retries + dead-letter queue | 2026-04-28 |
| [#29](https://github.com/iFrugal/notification-service/pull/29) | feat(dd-12): rate limiting per (tenant, caller, channel) | 2026-04-28 |
| [#28](https://github.com/iFrugal/notification-service/pull/28) | feat(kafka): X-Service-Id header propagates into callerId | 2026-04-27 |
| [#27](https://github.com/iFrugal/notification-service/pull/27) | docs(progress): mark Phase 4 / DD-11 done after PR #26 merge | 2026-04-27 |
| [#26](https://github.com/iFrugal/notification-service/pull/26) | feat(dd-11): caller identity via X-Service-Id | 2026-04-27 |
| #25 | docs(dd-10): mark DECIDED — full idempotency rollout shipped | 2026-04-27 |
| #24 | feat(idempotency): X-Idempotent-Replay header + README REST API docs | 2026-04-27 |
| #23 | chore: SonarCloud configuration and README badges | 2026-04-27 |
| #22 | test(idempotency): JUnit 5 + AssertJ + Mockito tests | 2026-04-27 |
| #21 | feat(idempotency): integrate IdempotencyStore into send path + 409 handler | 2026-04-27 |

_(older entries pruned — full history in `git log` and the
[DD change log](./design-decisions/00-decision-log.md#change-history))_

---

## How to use this file when starting a new session

1. Read this file first.
2. Check the **Status snapshot** for the current branch + version.
3. Look at **Phases** for the next `[ ]` or `[~]` item.
4. Look at **Open PRs** for anything that needs review / merge attention.
5. Update the relevant rows after every meaningful step.
