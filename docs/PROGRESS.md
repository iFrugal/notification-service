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
- **Last updated:** 2026-04-28 (DD-13 retries + DLQ PR open)

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

### Phase 7 — Retries + DLQ (DD-13) ← in flight

- [~] Single PR — synchronous retry with classified failures + bounded
      in-memory DLQ
  - [x] DD-13 design doc + decision-log entry
  - [x] `FailureType` enum + `SendResult.failureType` (backwards-compat
        defaults to `UNKNOWN`); `RetryPredicate` SPI in
        `notification-api`
  - [x] `RetryProperties` + `DeadLetterProperties` nested config (both
        opt-in, validated)
  - [x] `RetryExecutor` — exponential backoff with jitter, custom
        helper rather than Resilience4j (DD-13 §"Why a custom helper")
  - [x] `DeadLetterStore` SPI in `notification-api` +
        `InMemoryDeadLetterStore` (Caffeine bounded LRU) in
        `notification-core`
  - [x] `DefaultNotificationService` wraps provider call in retry
        executor; pushes exhausted/permanent failures to DLQ; rate-limit
        + idempotency invariants preserved (one token, one lock per
        logical send)
  - [x] `AdminController` exposes `GET /admin/dead-letter` with config
        + recent entries (PII-safe)
  - [x] Tests — `RetryExecutorTest` (10),
        `InMemoryDeadLetterStoreTest` (4),
        `DefaultNotificationServiceRetryTest` (8). 82 total tests
        across api/core/rest. Full reactor verify green
  - [x] README — Features bullet, "Retries + Dead-Letter" section,
        admin row
  - [x] PR raised: [#30](https://github.com/iFrugal/notification-service/pull/30)

### Phase 8+ — Queued

- [ ] Distributed rate limit + Redis-backed `IdempotencyStore` (DD-14 — bundle the two)
- [ ] DLQ replay endpoint with auth (re-submit by request id, with `replay-of` reference)
- [ ] Provider modules opt in to `FailureType` classification (Twilio 4xx mapping, SES throttling, etc.)
- [ ] Webhook callbacks for async delivery status (SES, Twilio, FCM)
- [ ] OpenAPI / Swagger schema generation in CI

---

## Open PRs

| # | Title | Branch | Status | Notes |
|---|-------|--------|--------|-------|
| [#30](https://github.com/iFrugal/notification-service/pull/30) | feat(dd-13): retries + dead-letter queue | `feat/dd-13-retries-dlq` | **awaiting review/merge** | Phase 7 — opt-in retry with classified failures + bounded in-memory DLQ |

---

## Recently merged

| PR | Title | Merged |
|----|-------|--------|
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
