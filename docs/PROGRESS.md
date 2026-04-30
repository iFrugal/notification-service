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
- **Last updated:** 2026-04-30 IST (OpenAPI + Swagger PR open). All
  dates in this file are local IST (UTC+5:30) since that's where the
  work is happening; UTC equivalents differ by ~5h30m.

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

### Phase 9 — OpenAPI / Swagger generation ← in flight

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
  - [ ] Branch pushed + PR opened + URL captured here

### Phase 10+ — Queued

- [ ] Distributed rate limit + Redis-backed `IdempotencyStore` (DD-14 — bundle the two)
- [ ] DLQ replay endpoint with auth (re-submit by request id, with `replay-of` reference)
- [ ] Webhook callbacks for async delivery status (SES bounce / complaint, Twilio status callback, FCM delivery)
- [ ] Jackson 2 → Jackson 3 migration (Boot 4's autoconfig defaults are Jackson 3; we still pin Jackson 2 in the parent POM)

---

## Open PRs

| # | Title | Branch | Status | Notes |
|---|-------|--------|--------|-------|
_(Phase 9 PR — see notes below; will be flagged here once raised)_

---

## Recently merged

| PR | Title | Merged |
|----|-------|--------|
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
