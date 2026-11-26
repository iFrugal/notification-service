# Notification Service - Design Decision Log

This document tracks all architectural and design decisions made during the development of the notification service.

## Decision Status Legend
- **DECIDED** - Final decision made, documented with reasoning
- **OPEN** - Under discussion, needs input
- **PARKED** - Deferred for later discussion

---

## Decisions Summary

| # | Topic | Status | Decision | Document |
|---|-------|--------|----------|----------|
| 1 | Maven Module Structure | DECIDED | Granular modules, no unnecessary API modules, SPI support | [01-module-structure.md](./01-module-structure.md) |
| 2 | Notification Request Structure | DECIDED | tenantId, channel, provider, polymorphic Recipient | [02-notification-request.md](./02-notification-request.md) |
| 3 | Multi-Tenancy Approach | DECIDED | Header-based (`X-Tenant-Id`) for REST and Kafka | [03-multi-tenancy.md](./03-multi-tenancy.md) |
| 4 | Template Engine | DECIDED | Wrapper over `persistence-utils` TemplateEngine | [04-template-engine.md](./04-template-engine.md) |
| 5 | Channel & Provider Registration | DECIDED | Hybrid: `beanName` + `fqcn`, fail-fast on startup | [05-provider-registration.md](./05-provider-registration.md) |
| 6 | External Provider Discovery | DECIDED | No SPI needed; `beanName`/`fqcn` explicit config; lifecycle methods | [06-spi-discovery.md](./06-spi-discovery.md) |
| 7 | Audit Persistence | DECIDED | Use `persistence-api` with pluggable impl | [07-audit-persistence.md](./07-audit-persistence.md) |
| 8 | Starter vs Standalone Packaging | DECIDED | Single Docker image, config-driven, Configuration API | [08-packaging-modes.md](./08-packaging-modes.md) |
| 10 | Idempotency Key (First-Class) | DECIDED | Optional `idempotencyKey` on request, scoped `(tenantId, callerId, key)`, Caffeine default store with Redis SPI | [10-idempotency.md](./10-idempotency.md) |
| 11 | Caller Identity (`X-Service-Id`) | DECIDED | Optional `X-Service-Id` header → `callerId` on request/response/audit, closes DD-10 callerId gap, opt-in caller registry with `strict` rejection mode | [11-caller-identity.md](./11-caller-identity.md) |
| 12 | Rate Limiting | DECIDED | Opt-in, per-`(tenant, caller, channel)` token bucket via Bucket4j; checked inside `DefaultNotificationService.send()` so REST + Kafka share enforcement; HTTP 429 + `Retry-After` on REST | [12-rate-limiting.md](./12-rate-limiting.md) |
| 13 | Retries + DLQ | DECIDED | Opt-in in-process retry with classified failures (TRANSIENT/PERMANENT/UNKNOWN), exponential backoff + jitter; pluggable `DeadLetterStore` SPI with bounded in-memory default; one rate-limit token + one idempotency lock per logical send (not per attempt) | [13-retries-and-dlq.md](./13-retries-and-dlq.md) |
| 14 | Distributed Redis backends | DECIDED | New opt-in `notification-redis` module providing Redis-backed implementations of the DD-10 `IdempotencyStore`, DD-12 `RateLimiter`, and DD-13 `DeadLetterStore` SPIs. Spring Data Redis + Lettuce + bucket4j-redis. Each backend independently toggleable. | [14-distributed-redis-backends.md](./14-distributed-redis-backends.md) |
| 15 | DLQ Replay | DECIDED | `POST /admin/dead-letter/{requestId}/replay` re-submits a dead-lettered notification with a fresh `requestId` + `idempotencyKey` and a server-set `replayOf` reference back to the original; entry removed from DLQ on successful send, kept on failure. SPI extended with `findByRequestId` + `remove`. | [15-dlq-replay.md](./15-dlq-replay.md) |
| 16 | Webhook delivery callbacks | DECIDED | Opt-in `/webhooks/{provider}/{path}` surface ingests provider delivery callbacks. Twilio status (HMAC-SHA1 verification) + SES via SNS (AWS SDK signature verification) in this DD; FCM noted as not-supported. Parsed events flow to a `DeliveryEventListener` SPI; default impl logs at INFO. New `DeliveryStatus` enum + `DeliveryEvent` record. Each provider independently toggleable. | [16-webhook-delivery-callbacks.md](./16-webhook-delivery-callbacks.md) |
| 17 | Persistent DeliveryEventStore | DECIDED | Opt-in `DeliveryEventStore` SPI extends `DeliveryEventListener` so a single bean satisfies both seams. In-memory Caffeine default in `notification-core`; Redis LPUSH+LTRIM default in `notification-redis`. New `GET /admin/delivery-events` admin endpoint with `?providerMessageId` filter and PII-safe attribute redaction by default. | [17-delivery-event-store.md](./17-delivery-event-store.md) |
| 18 | Joined audit ↔ delivery query | DECIDED | `GET /admin/delivery-events?requestId=…` walks `NotificationAuditService.findByRequestId` → `DeliveryEventStore.findByProviderMessageId`. New `auditState` field distinguishes incomplete (still in flight) from complete (no callbacks yet). 404 when audit record absent; composition of DD-15 + DD-17 in the controller, no new SPI. | [18-audit-delivery-join.md](./18-audit-delivery-join.md) |
| 19 | Bulk DLQ replay | DECIDED | `POST /admin/dead-letter/replay-batch?tenantId=…&dryRun=…&limit=…` replays many DLQ entries in one call. Mandatory tenantId (blast-radius safety). Default limit 100, max 1000. Per-entry results in the response so operators see individual failures. Successful entries removed; failed entries kept. | [19-bulk-dlq-replay.md](./19-bulk-dlq-replay.md) |
| 20 | Admin audit browse | DECIDED | `GET /admin/audit/{requestId}` returns the audit record; `GET /admin/audit/recent?tenantId=…&limit=…` returns the most-recent N. New `findRecent` default method on `NotificationAuditService` returns `Optional.empty()` so existing impls compile; controller maps empty to "backend does not support listing". | [20-admin-audit-browse.md](./20-admin-audit-browse.md) |
| 21 | Actuator health indicators | DECIDED | One `HealthIndicator` per SPI (DLQ, idempotency, rate-limit, delivery events). Each `@ConditionalOnClass(HealthIndicator)` + `@ConditionalOnProperty` matching its SPI's enable flag. DLQ near-fullness reports `OUT_OF_SERVICE` (not `DOWN`) at configurable threshold so liveness probes don't restart the pod. Indicators surface at `/actuator/health/{component}`. | [21-health-indicators.md](./21-health-indicators.md) |
| 22 | Micrometer metrics | DECIDED | Typed `NotificationMetrics` helper wraps `MeterRegistry`. Counters across the send path (sends, retries, rate-limit denied, idempotency replay, DLQ added, webhook events + signature failures) plus gauges (DLQ size, delivery-events size) and a send-duration timer. Tag cardinality kept bounded — `tenant` tag only on `idempotency.replay`. | [22-metrics.md](./22-metrics.md) |
| 23 | Per-channel retry + rate-limit overrides | DECIDED | New `byChannel` map on `RetryProperties` and `RateLimitProperties`. Resolution precedence (rate-limit): most-specific tuple override → `byChannel` default → global default. Retry: `byChannel` rule → global. `RetryExecutor` gets a channel-aware `execute(Channel, Supplier)` overload. Backwards-compatible — top-level retry fields stay; new slot composes on top. | [23-per-channel-overrides.md](./23-per-channel-overrides.md) |
| 24 | FCM HTTP v1 push provider | DECIDED | New `push-provider-fcm` module with no dependency beyond `notification-api`: HTTP v1 through its own `FcmHttpTransport` SPI (JDK HTTP/2 client by default), service-account JWT bearer auth built in, ADC and workload identity in an optional `push-provider-fcm-google-auth` adapter. Exactly one target (token, tokens, FID, topic, condition); whitelisted metadata overrides; per-token results and an `all`/`any` policy for `deviceTokens`; FCM errors classified per DD-13/DD-25 with Retry-After; invalid tokens published as `BOUNCED`/`INVALID_TARGET` events with a token hash. | [24-fcm-http-v1-provider.md](./24-fcm-http-v1-provider.md) |
| 25 | Ambiguous failures, retry-after cap, provider-originated delivery events | DECIDED | New `FailureType.AMBIGUOUS` (the provider may have accepted the message) is not retried by the default predicate and goes to the DLQ; SES, SMTP and Twilio read timeouts map to it. A `Retry-After` hint beyond `max-retry-after` (default `max-delay`) stops retrying instead of being cut short. Providers implementing `DeliveryEventEmitter` get a `DeliveryEventPublisher` before `configure()` and publish events to the existing listeners; invalid targets are `BOUNCED` with reason `INVALID_TARGET`. | [25-ambiguous-failures-retry-after-provider-events.md](./25-ambiguous-failures-retry-after-provider-events.md) |
| 26 | Library-owned FreeMarker configuration | DECIDED | `NotificationTemplateEngine` builds its own FreeMarker `Configuration` with persistence-utils' settings (2.3.23, UTF-8, `Locale.US`, `computer`), so output is unchanged. A `ResourceLoader`-backed loader makes includes and imports resolve against `base-path`, relative to the including template, with the id rule on every segment. Errors are rethrown once as `TemplateRenderException`, never logged by FreeMarker; a recording exception handler rebases the blamed expression onto the data model by identity, so values passed through macro or function parameters report their data path. `legacy-helpers` (default `true`, deprecated) keeps `eval`/`file`/`js`/`serde_*` and unrestricted `?new`; `false` drops them and uses `SAFER_RESOLVER`. | [26-library-owned-freemarker-configuration.md](./26-library-owned-freemarker-configuration.md) |
| 27 | Validate before dispatch | DECIDED | New `TemplateRenderException` (reasons `NOT_FOUND`, `INVALID_TEMPLATE`, `MODEL_INVALID`, `EMPTY_OUTPUT`; `TemplateNotFoundException` is the `NOT_FOUND` subclass). `notification.template.validate-before-dispatch` (default `false`, planned `true` in 2.0) renders first in `send`, before idempotency, rate limit and audit, refuses blank email subjects and empty bodies, throws to the caller with nothing stored, and reuses the content for dispatch (rendered once per send). REST answers 422 with `error`, `reason`, `templateId`, `path`; Kafka logs one WARN and commits without DLQ. | [27-validate-before-dispatch.md](./27-validate-before-dispatch.md) |
| 28 | Default-tenant provider fallback | DECIDED | `notification.tenant-fallback.enabled` (default `false`) lets a tenant with no entry under `notification.tenants` use the providers of `notification.default-tenant`. Only provider resolution (`getProvider`, `isProviderRequiredInRequest`) falls back, reusing the default tenant's instances; idempotency, rate limits, templates and audit keep the real tenant id. A missing default tenant still fails with `TENANT_NOT_FOUND`; listings never show the unconfigured tenant. | [28-default-tenant-provider-fallback.md](./28-default-tenant-provider-fallback.md) |
| 29 | Lazy provider initialisation | DECIDED | `notification.providers.lazy-init` (default `false`) skips configure/init at startup; the first lookup builds the provider, with concurrent callers sharing one build. A failed build is not cached: the registry returns a stand-in whose send fails with `PROVIDER_NOT_CONFIGURED` as `TRANSIENT`, so the unchanged service applies retry, DLQ and audit, and a retry rebuilds. Admin health shows `NOT_INITIALIZED` (still `UP`) and `INIT_FAILED` (`DEGRADED`). | [29-lazy-provider-initialisation.md](./29-lazy-provider-initialisation.md) |
| 30 | Rate-limit bandwidths, bucket scope and refunds | DECIDED | `RateLimitRule` gains `bandwidths` (all must allow; exclusive with the single-band fields, enforced at startup) and `scope` (`TENANT_CALLER` default, or `CHANNEL`: one bucket per channel per rule, applied after rule precedence inside the limiter). `RateLimiter.refund(RateLimitKey)` default no-op, implemented in-memory and in Redis (one token, never above capacity). `notification.rate-limit.refund-unsent` (default `false`): the service refunds the consumed key when the idempotency release policy (DD-32) released a failed send's key, never for `AMBIGUOUS` or `UNKNOWN`. Shared `RateLimitRuleResolver` for both limiters. | [30-rate-limit-bandwidths-scope-refund.md](./30-rate-limit-bandwidths-scope-refund.md) |
| 31 | Failure type, provider metadata and retry hint on NotificationResponse | DECIDED | `NotificationResponse` gains nullable `failureType`, `providerMetadata` and `retryAfter` (ISO-8601 `Duration`; all omitted from JSON when null; 1.2.0 constructor kept), filled from the provider's `SendResult` (`PERMANENT` for `NotificationException`, `UNKNOWN` for unexpected errors) and kept in stored idempotency responses and replays. ACS keeps the DD-25 classification (timeouts `TRANSIENT`, 401/403 `PERMANENT`); the provider property `timeout-failure-type: ambiguous` opts in to `AMBIGUOUS` for read timeouts, resets and `TimeoutException`, connect failures staying `TRANSIENT`. | [31-failure-type-on-notification-response.md](./31-failure-type-on-notification-response.md) |
| 32 | Idempotency fingerprint, key reuse, failure and release policy, in-progress timeout | DECIDED | SHA-256 fingerprint of a canonical JSON of what is sent (NFC, sorted keys, numbers as parsed, nulls kept; correlation id and metadata excluded) stored on `IdempotencyRecord` in all three stores (JDBC: optional nullable column, probed at startup; missing = WARN with the `ALTER`, or startup failure with `reject-key-reuse`). `reject-key-reuse` (default `false`) throws `IdempotencyKeyReusedException` (409; null fingerprint never compared). `failure-policy` `RELEASE_ALL` (default) / `KEEP` / `RELEASE_TRANSIENT`, `retry-after-failure` a deprecated alias, applied through the replaceable `IdempotencyReleasePolicy` SPI. `in-progress-timeout` (default unset) settles a stale claim by compare-and-set as `FAILED` / `IN_PROGRESS_TIMEOUT` / `AMBIGUOUS`, replayed and never released. `MessageScrubber` scrubs persisted error messages; the stored failure keeps one only with `store-scrubbed-error-message` (default `false`). | [32-idempotency-fingerprint-failure-policy.md](./32-idempotency-fingerprint-failure-policy.md) |
| 33 | Status view over the audit record | DECIDED | `NotificationService.find(tenantId, requestId)` and `findByIdempotencyKey(tenantId, callerId, key)` return a `NotificationView`: the audit record joined with the latest delivery event, never recipient addresses. New `NotificationAuditStore` SPI with in-memory (bounded) and JDBC (`<prefix>audit`, purged, `retention-days`) stores behind `notification.audit.enabled`; `StoreBackedAuditService` swallows write errors; `NotificationAudit` gains idempotency key, recipient count, failure type, failed/updated timestamps; no Redis audit store. | [33-status-view.md](./33-status-view.md) |
| 34 | Channel-agnostic recipient guard | DECIDED | `RecipientGuard` SPI with a configuration-driven default behind `notification.recipient-guard.enabled` (default `false`): per channel `allowed` (`@domain`, address, digits-normalised phone) and `redirect-to` (must be allowed; unset drops), to/cc/bcc de-duplication, email subject prefix `[redirected: {originals}] `. Runs after the idempotency claim on the original request; audit and provider see only the guarded recipient; fully blocked sends fail `RECIPIENT_BLOCKED` / `PERMANENT` before the provider. Push not guarded. | [34-recipient-guard.md](./34-recipient-guard.md) |
| 35 | WhatsApp channel and Meta provider | DECIDED | New vendor-neutral `whatsapp-core` (template registry SPI with a YAML default, `WhatsAppTemplateMessage`, WhatsApp parameter rules raising `TemplateValidationException`, the `WHATSAPP` `ChannelContentRenderer`) and `whatsapp-provider-meta` (Graph API template sends over its own `MetaHttpTransport`, no retry loop, documented errorCodes per failure class, health DOWN on credential or account errors, `MetaCredentialsProvider` bean or static credentials, signed status webhook). Core gains the per-channel renderer hook and `RenderedContent.payload`; `DeliveryStatus` gains `SENT` and `READ`. Free-text window, inbound messages and other vendors are out of scope. | [35-whatsapp-channel-and-meta-provider.md](./35-whatsapp-channel-and-meta-provider.md) |
| 36 | Webhook handler SPI | DECIDED | Servlet-free `WebhookHandler` / `WebhookRequest` / `WebhookResponse` in `notification-api`; core `WebhookDispatcher` routes by handler id (404 when unknown), publishes events to the listeners and records the webhook metrics; the REST `WebhookController` becomes one `/{handlerId}/**` route, with Twilio and SES as handlers on unchanged paths. Applications without the REST module mount the dispatcher themselves. | [36-webhook-handler-spi.md](./36-webhook-handler-spi.md) |

---

## Key Design Principles

1. **Explicit over Implicit**: Configuration in YAML, not magic auto-discovery
2. **Fail-Fast**: Validate all providers at startup
3. **Reuse Existing**: Leverage `persistence-utils` (`InitDTO`, `ClassUtils`, `TemplateEngine`, `TenantContext`)
4. **Pluggable**: External providers via `beanName` or `fqcn`
5. **Multi-Tenant First**: All features tenant-aware from the start
6. **Config-Driven**: Single Docker image, behavior controlled by YAML

---

## Technology Choices

| Aspect | Choice | Reasoning |
|--------|--------|-----------|
| Template Engine | FreeMarker (via `persistence-utils`) | Already in use, powerful |
| Multi-tenancy | `TenantContext` ThreadLocal | Proven pattern, reuse existing |
| Provider Resolution | `beanName` + `fqcn` | Follows `InitDTO` pattern |
| Audit Storage | `persistence-api` | Pluggable (MongoDB, JDBC, etc.) |
| Configuration | Spring Boot YAML | Standard, environment variable support |

---

## Change History

| Date | Decision # | Change | Author |
|------|------------|--------|--------|
| 2025-01-17 | 1-4, 7 | Initial decisions captured | - |
| 2025-01-17 | 5 | Decided: `beanName` + `fqcn` hybrid, fail-fast | - |
| 2025-01-17 | 6 | Decided: No SPI, lifecycle methods (init/destroy) | - |
| 2025-01-17 | 8 | Decided: Single Docker image, Config API with masking | - |
| 2026-04-23 | 10 | Proposed: optional `idempotencyKey` request field, Caffeine default store with SPI for Redis | Abhijeet |
| 2026-04-27 | 10 | Decided: full rollout merged — SPI + Caffeine impl, service integration, 409 handler, X-Idempotent-Replay header, JUnit 5 tests, README docs | Abhijeet |
| 2026-04-27 | 11 | Decided: optional `X-Service-Id` header → `callerId` on request/response/audit; closes DD-10 callerId gap; opt-in caller registry with `strict` rejection mode | Abhijeet |
| 2026-04-27 | 12 | Decided: opt-in `(tenant, caller, channel)` token-bucket rate limiting via Bucket4j; enforced in `DefaultNotificationService.send()`; HTTP 429 + `Retry-After` on REST | Abhijeet |
| 2026-04-27 | 13 | Decided: opt-in synchronous retry with classified failures + exponential backoff + jitter; pluggable DLQ SPI with bounded in-memory default; rate-limit / idempotency invariants hold per logical send not per attempt | Abhijeet |
| 2026-04-30 | 14 | Decided: new opt-in `notification-redis` module with Redis-backed implementations of DD-10/12/13 SPIs; per-feature toggles; Spring Data Redis + Lettuce + bucket4j-redis; Testcontainers integration tests | Abhijeet |

---

## Next Steps

1. ✅ All design decisions finalized
2. ⏳ Create Maven module structure
3. ⏳ Implement notification-service project
