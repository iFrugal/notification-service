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
- **Last updated:** 2026-04-27 (Kafka X-Service-Id PR open)

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

### Phase 5 — Kafka picks up `X-Service-Id` ← in flight

- [~] Single PR — extends DD-11 to the Kafka transport
  - [x] `NotificationKafkaListener` reads `X-Service-Id` header,
        stamps onto `request.callerId` (body wins per DD-11)
  - [x] `NotificationKafkaListenerTest` — 6 tests covering both-headers,
        body-wins-over-header, header-absent, blank-header,
        default-tenant fallback, exception-still-resets-context
  - [x] README — Kafka section explains both headers + admission semantics
  - [ ] Branch pushed + PR opened + URL captured here

### Phase 6+ — Queued

- [ ] Redis-backed `IdempotencyStore` (DD-10 mentions as foreseen SPI consumer)
- [ ] Rate limiting (per tenant + per caller)
- [ ] Retries / DLQ for transient provider failures
- [ ] Webhook callback for delivery status (where the provider supports it)
- [ ] OpenAPI / Swagger schema generation in CI

---

## Open PRs

| # | Title | Branch | Status | Notes |
|---|-------|--------|--------|-------|

_(none currently — flag here as soon as a PR is raised)_

---

## Recently merged

| PR | Title | Merged |
|----|-------|--------|
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
