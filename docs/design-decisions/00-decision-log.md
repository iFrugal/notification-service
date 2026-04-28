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

---

## Next Steps

1. ✅ All design decisions finalized
2. ⏳ Create Maven module structure
3. ⏳ Implement notification-service project
