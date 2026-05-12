# Feature Matrix — what to add, what to configure

> **Read this first** if you're integrating `notification-service` and asking
> *"what do I actually need?"*
>
> Every cross-cutting feature is **opt-in by default**. The base starter
> sends notifications and that's it. Pull the features you want, set their
> flag to `true`, you're done.

## Legend

| Symbol | Meaning |
|--------|---------|
| 🟢 | On out of the box — nothing to enable |
| 🔵 | Off by default — one property to flip |
| 📦 | Extra Maven dependency required |
| 🔌 | Extra dependency optional (only if you want the named backend) |
| ⚠️ | Has runtime requirements (Redis, Kafka broker, provider credentials) |

> All Maven coordinates use group `com.github.ifrugal` and the current
> released version (see badges on the README). For brevity the snippets
> below show only `<artifactId>`.

---

## 1. The starter (always)

This is the single dependency that brings the service in:

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>notification-spring-boot-starter</artifactId>
    <version>1.0.1</version>
</dependency>
```

| What you get | Status | Notes |
|--------------|--------|-------|
| Core service (`NotificationService.send(...)`) | 🟢 | Always present |
| Template engine (FreeMarker) | 🟢 | Tenant-aware lookup; configurable via `notification.template.*` |
| Multi-tenant routing (`X-Tenant-Id`) | 🟢 | DD-03 |
| Provider registry + lifecycle | 🟢 | DD-05 / DD-06 |
| In-memory idempotency store (Caffeine) | 🟢 | DD-10; `notification.idempotency.enabled` defaults to `true` |
| REST transport (`/api/v1/notifications`, `/api/v1/admin/*`) | 🟢 | DD Phase 9 — module is `<optional>true</optional>` in the starter; **pulled by default when present** |

**Minimum `application.yml`:**

```yaml
notification:
  default-tenant: default
  tenants:
    default:
      channels:
        email:
          enabled: true
          providers:
            smtp:
              default: true
              properties:
                host: smtp.gmail.com
                port: 587
                username: ${SMTP_USER}
                password: ${SMTP_PASS}
```

You also need **at least one provider JAR** (next section).

---

## 2. Channels & providers

Each channel has its own JAR; each provider for that channel is a
**separate** sub-JAR. Pull only the ones you use — an SMTP-only
deployment doesn't transitively inherit AWS or Twilio SDKs.

### 2a. Email channel

| Provider | Artifact | When to use | Required `application.yml` properties |
|----------|----------|-------------|----------------------------------------|
| SMTP | `email-provider-smtp` | Self-hosted SMTP relay or Gmail / SendGrid SMTP | `host`, `port`, `username`, `password`, `starttls` |
| AWS SES | `email-provider-ses` | AWS-native email | `aws-region`, `aws-access-key`, `aws-secret-key` (or IAM role on EC2) |

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>email-provider-smtp</artifactId>
    <version>1.0.1</version>
</dependency>
```

```yaml
notification:
  tenants:
    default:
      channels:
        email:
          enabled: true
          providers:
            smtp:
              default: true
              properties:
                host: smtp.gmail.com
                port: 587
                username: ${SMTP_USER}
                password: ${SMTP_PASS}
                starttls: true
```

### 2b. SMS channel

| Provider | Artifact | When to use | Required properties |
|----------|----------|-------------|---------------------|
| Twilio | `sms-provider-twilio` | Global SMS, premium quality | `account-sid`, `auth-token`, `from-number` |
| AWS SNS | `sms-provider-sns` | AWS-native SMS, cheaper for transactional | `aws-region`, AWS creds |

### 2c. WhatsApp channel

| Provider | Artifact | When to use | Required properties |
|----------|----------|-------------|---------------------|
| Twilio | `whatsapp-provider-twilio` | Twilio's WhatsApp Business API | `account-sid`, `auth-token`, `from-number` (WhatsApp sandbox or approved sender) |
| Meta | `whatsapp-provider-meta` | Direct Meta WhatsApp Cloud API | `phone-number-id`, `access-token`, `business-account-id` |

### 2d. Push channel

| Provider | Artifact | When to use | Required properties |
|----------|----------|-------------|---------------------|
| Firebase FCM | `push-provider-fcm` | Cross-platform (Android + iOS + web) push | `credentials-file` (path to FCM service-account JSON) |
| Apple APNs | `push-provider-apns` | iOS-only push direct to Apple | `key-file`, `key-id`, `team-id`, `bundle-id` |

---

## 3. Cross-cutting features

Each row = one independently-toggleable feature. Default-off unless noted.
All have an in-memory default; most have an optional Redis backend.

| Feature | DD | Default | Enable flag | In-memory default | Optional Redis backend |
|---------|----|---------|-------------|-------------------|------------------------|
| **Idempotency** | DD-10 | 🟢 On | `notification.idempotency.enabled: true` | Caffeine (in `notification-core`, always present) | `notification.redis.idempotency.enabled: true` |
| **Caller identity** (`X-Service-Id`) | DD-11 | 🟢 On (registry off) | `notification.caller-registry.enabled: true` to enforce | n/a | n/a |
| **Rate limiting** | DD-12 | 🔵 Off | `notification.rate-limit.enabled: true` | Bucket4j (in `notification-core`) | `notification.redis.rate-limit.enabled: true` |
| **Retries** | DD-13 | 🔵 Off | `notification.retry.enabled: true` | Built-in | n/a |
| **Dead-letter queue** | DD-13 | 🔵 Off | `notification.dead-letter.enabled: true` | Caffeine LRU (in `notification-core`) | `notification.redis.dead-letter.enabled: true` |
| **Per-channel retry + rate-limit overrides** | DD-23 | n/a | composes on top of retry / rate-limit when those are enabled | n/a | n/a |
| **Webhook ingestion** (Twilio + SES) | DD-16 | 🔵 Off | `notification.webhooks.enabled: true` + per-provider | n/a | n/a |
| **Delivery event store** | DD-17 | 🔵 Off | `notification.delivery-events.enabled: true` | Caffeine LRU | `notification.redis.delivery-events.enabled: true` |
| **Audit persistence** | DD-07 | 🔵 Off | `notification.audit.enabled: true` | No-op (logs only) | Bring your own via `persistence-api` |

### 3a. Idempotency (DD-10)

**Already on by default with a Caffeine in-memory store.** Customers want
multi-pod? Flip the Redis backend:

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>notification-redis</artifactId>
    <version>1.0.1</version>
</dependency>
```

```yaml
notification:
  idempotency:
    enabled: true                  # default true; reaffirm explicitly
    ttl: 24h
    max-entries: 100000

  redis:
    key-prefix: notification-svc
    idempotency:
      enabled: true                # ⚠️ requires Redis reachable

spring:
  data:
    redis:
      host: redis.internal
      port: 6379
```

Send-side: include `idempotencyKey` on `NotificationRequest`. Replay of a
completed key returns `200` with header `X-Idempotent-Replay: true`.
Concurrent duplicate returns `409`.

### 3b. Rate limiting (DD-12 + DD-23)

```yaml
notification:
  rate-limit:
    enabled: true
    default-rule:                  # bucket per (tenant, caller, channel)
      capacity: 200
      refill-tokens: 100
      refill-period: 1s
    by-channel:                    # DD-23: per-channel default
      sms:
        capacity: 20
        refill-tokens: 5
        refill-period: 1s
    overrides:                     # most-specific wins
      - tenant: acme
        caller: marketing
        channel: sms
        capacity: 50
        refill-tokens: 10
        refill-period: 1s
```

REST denial: HTTP `429` + `Retry-After`. Kafka: drop-and-commit (no
requeue amplification).

### 3c. Retries + Dead-Letter Queue (DD-13 + DD-15 + DD-19 + DD-23)

```yaml
notification:
  retry:
    enabled: true
    max-attempts: 3                # global default
    initial-delay: 1s
    multiplier: 2.0
    max-delay: 30s
    jitter: 0.5
    by-channel:                    # DD-23
      sms:                         # SMS is expensive — tight bound
        max-attempts: 2
      email:                       # email is cheap — generous
        max-attempts: 5

  dead-letter:
    enabled: true
    max-entries: 1000              # in-memory bound; Redis tunes separately
```

Operator endpoints (DD-15 + DD-19):
- `GET /api/v1/admin/dead-letter` — recent failures (PII-safe)
- `POST /api/v1/admin/dead-letter/{requestId}/replay` — single replay
- `POST /api/v1/admin/dead-letter/replay-batch?tenantId=acme&dryRun=true` — bulk

For distributed (multi-pod) DLQ:

```yaml
notification:
  redis:
    dead-letter:
      enabled: true
      max-entries: 10000
```

### 3d. Webhooks — provider delivery callbacks (DD-16 + DD-17)

```yaml
notification:
  webhooks:
    enabled: true
    twilio:
      enabled: true
      auth-token: ${TWILIO_AUTH_TOKEN}
      signature-verification: true    # leave on in production
    ses:
      enabled: true
      topic-arn: arn:aws:sns:us-east-1:0:my-ses-topic
      signature-verification: true

  delivery-events:                 # store for querying via admin endpoint
    enabled: true
    max-entries: 5000
```

URLs to register with providers:
- Twilio status callback URL: `https://your.host/api/v1/webhooks/twilio/status`
- SES → SNS topic subscription URL: `https://your.host/api/v1/webhooks/ses/sns`

Query operator endpoints:
- `GET /api/v1/admin/delivery-events`
- `GET /api/v1/admin/delivery-events?requestId=req-abc` — joins via audit (DD-18)

**FCM is not supported** — Firebase doesn't ship per-message webhooks today.

---

## 4. Distributed mode (multi-pod)

For deployments running 2+ pods, the in-memory stores (idempotency,
rate-limit buckets, DLQ, delivery events) live in each pod separately.
Add the Redis backend so all pods share one source of truth.

### 4a. Add the JAR

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>notification-redis</artifactId>
    <version>1.0.1</version>
</dependency>
```

### 4b. Enable per-feature

| Redis-backed | Property | What it replaces |
|--------------|----------|-------------------|
| Idempotency | `notification.redis.idempotency.enabled: true` | `CaffeineIdempotencyStore` |
| Rate limiting | `notification.redis.rate-limit.enabled: true` | `Bucket4jRateLimiter` (in-process) |
| Dead-letter queue | `notification.redis.dead-letter.enabled: true` | `InMemoryDeadLetterStore` |
| Delivery event store | `notification.redis.delivery-events.enabled: true` | `InMemoryDeliveryEventStore` |

### 4c. Connection

Uses Spring Data Redis defaults — point it at your Redis:

```yaml
spring:
  data:
    redis:
      host: redis.internal
      port: 6379
      password: ${REDIS_PASSWORD:}    # if your Redis has auth

notification:
  redis:
    key-prefix: notification-svc     # avoid collisions on shared Redis
```

### 4d. Read raw entries

Operators can `redis-cli LRANGE notification-svc:dlq 0 -1` directly —
all stored values are human-readable JSON for forensics. Same for
delivery events under `<prefix>:delivery-events`.

---

## 5. Observability (DD-21 + DD-22)

### 5a. Health indicators (DD-21)

Auto-registered when the corresponding SPI is enabled. Each indicator
participates in the rolled-up `/actuator/health`:

| Indicator | Surfaces at | Enabled when |
|-----------|-------------|--------------|
| DLQ | `/actuator/health/dlq` | `notification.dead-letter.enabled: true` |
| Idempotency | `/actuator/health/idempotency` | `notification.idempotency.enabled: true` |
| Rate limiter | `/actuator/health/rateLimit` | `notification.rate-limit.enabled: true` |
| Delivery events | `/actuator/health/deliveryEvents` | `notification.delivery-events.enabled: true` |

DLQ flips to `Status.OUT_OF_SERVICE` at near-fullness — configurable:

```yaml
notification:
  health:
    dlq-near-full-percent: 80      # default
```

### 5b. Micrometer metrics (DD-22)

Auto-registered when `MeterRegistry` is on the classpath (it is, on
`notification-server`). No flag needed — Boot's
`management.metrics.enable.notification=false` disables.

| Meter | Type | Tags |
|-------|------|------|
| `notification.sends.total` | counter | channel, status |
| `notification.sends.duration` | timer | channel |
| `notification.retries.total` | counter | channel, attempt |
| `notification.rate-limit.denied.total` | counter | channel |
| `notification.idempotency.replay.total` | counter | tenant |
| `notification.dlq.added.total` | counter | channel, failureType |
| `notification.dlq.size` | gauge | (none) |
| `notification.delivery-events.received.total` | counter | provider, status |
| `notification.delivery-events.size` | gauge | (none) |
| `notification.webhook.signature.failed.total` | counter | provider |

Standalone deployments ship with a Prometheus registry — scrape
`/actuator/prometheus`.

---

## 6. Transports

| Transport | JAR | Default | Enable | Notes |
|-----------|-----|---------|--------|-------|
| **REST** | `notification-rest` (pulled by starter) | 🟢 On | `notification.rest.enabled: true` (default) | Endpoints under `${notification.rest.base-path:/api/v1}/notifications` and `/admin/...` |
| **Kafka** | `notification-kafka` (📦 add explicitly) | 🔵 Off | `notification.kafka.enabled: true` | Honours `X-Tenant-Id` + `X-Service-Id` headers |
| **Programmatic** | `notification-api` (always) | 🟢 On | n/a | Inject `NotificationService` directly |

### Kafka consumer

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>notification-kafka</artifactId>
    <version>1.0.1</version>
</dependency>
```

```yaml
notification:
  kafka:
    enabled: true
    topic: notifications
    group-id: notification-service

spring:
  kafka:
    bootstrap-servers: kafka.internal:9092
    consumer:
      auto-offset-reset: earliest
```

---

## 7. Audit (DD-07)

```yaml
notification:
  audit:
    enabled: true
    store-request-payload: false      # PII concern; default off
    store-response-payload: false
    retention-days: 90
```

**You wire your own backend.** The default is `NoOpAuditService` (logs
only). Replace by registering a `NotificationAuditService` bean — see
DD-07. Admin browse (DD-20) at:
- `GET /api/v1/admin/audit/{requestId}`
- `GET /api/v1/admin/audit/recent?tenantId=acme`

---

## 8. OpenAPI / Swagger UI (Phase 9)

🟢 On by default via springdoc 3 (transitive of `notification-rest`).

- Schema: `${notification.rest.base-path:/api/v1}/../v3/api-docs`
- Swagger UI: `/swagger-ui/index.html`

Disable in production:

```yaml
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

---

## 9. Three worked examples

Copy-paste-able starting points. Each is the **full** dependency list.

### 9a. Smallest viable — single pod, email only

```xml
<dependencies>
    <dependency>
        <groupId>com.github.ifrugal</groupId>
        <artifactId>notification-spring-boot-starter</artifactId>
        <version>1.0.1</version>
    </dependency>
    <dependency>
        <groupId>com.github.ifrugal</groupId>
        <artifactId>notification-rest</artifactId>
        <version>1.0.1</version>
    </dependency>
    <dependency>
        <groupId>com.github.ifrugal</groupId>
        <artifactId>email-provider-smtp</artifactId>
        <version>1.0.1</version>
    </dependency>
</dependencies>
```

```yaml
notification:
  default-tenant: default
  tenants:
    default:
      channels:
        email:
          enabled: true
          providers:
            smtp:
              default: true
              properties:
                host: smtp.gmail.com
                port: 587
                username: ${SMTP_USER}
                password: ${SMTP_PASS}
                starttls: true
```

### 9b. SaaS deployment — multi-channel, with retry + DLQ + rate-limit

```xml
<dependencies>
    <dependency>
        <groupId>com.github.ifrugal</groupId>
        <artifactId>notification-spring-boot-starter</artifactId>
        <version>1.0.1</version>
    </dependency>
    <dependency>
        <groupId>com.github.ifrugal</groupId>
        <artifactId>notification-rest</artifactId>
        <version>1.0.1</version>
    </dependency>
    <dependency>
        <groupId>com.github.ifrugal</groupId>
        <artifactId>email-provider-ses</artifactId>
        <version>1.0.1</version>
    </dependency>
    <dependency>
        <groupId>com.github.ifrugal</groupId>
        <artifactId>sms-provider-twilio</artifactId>
        <version>1.0.1</version>
    </dependency>
</dependencies>
```

```yaml
notification:
  rate-limit:
    enabled: true
    default-rule: { capacity: 200, refill-tokens: 100, refill-period: 1s }
    by-channel:
      sms: { capacity: 20, refill-tokens: 5, refill-period: 1s }

  retry:
    enabled: true
    max-attempts: 3
    by-channel:
      sms:   { max-attempts: 2 }
      email: { max-attempts: 5 }

  dead-letter:
    enabled: true
    max-entries: 1000

  webhooks:
    enabled: true
    twilio:
      enabled: true
      auth-token: ${TWILIO_AUTH_TOKEN}

  delivery-events:
    enabled: true
```

### 9c. Production multi-pod — full distributed mode

Same dependencies as 9b **plus**:

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>notification-redis</artifactId>
    <version>1.0.1</version>
</dependency>
```

```yaml
notification:
  rate-limit: { enabled: true, ... }
  retry: { enabled: true, ... }
  dead-letter: { enabled: true }
  webhooks: { enabled: true, twilio: { ... } }
  delivery-events: { enabled: true }

  redis:
    key-prefix: notification-svc
    idempotency: { enabled: true }
    rate-limit:  { enabled: true }
    dead-letter:  { enabled: true, max-entries: 10000 }
    delivery-events: { enabled: true, max-entries: 50000 }

spring:
  data:
    redis:
      host: redis.internal
      port: 6379
      password: ${REDIS_PASSWORD}
```

---

## 10. Property reference (every flag in one place)

| Property | Default | Effect |
|----------|---------|--------|
| `notification.default-tenant` | `default` | Fallback `tenantId` when `X-Tenant-Id` header absent |
| `notification.rest.enabled` | `true` | REST controllers |
| `notification.rest.base-path` | `/api/v1` | Path prefix for all REST + admin + webhook surfaces |
| `notification.kafka.enabled` | `false` | Kafka consumer |
| `notification.audit.enabled` | `false` | Audit (default impl is no-op even when true; wire your own) |
| `notification.idempotency.enabled` | `true` | Idempotency dedup |
| `notification.idempotency.ttl` | `24h` | Key retention |
| `notification.idempotency.max-entries` | `100000` | In-memory bound |
| `notification.caller-registry.enabled` | `false` | Validate `X-Service-Id` against known list |
| `notification.caller-registry.strict` | `false` | Reject unknown callers with `403` |
| `notification.rate-limit.enabled` | `false` | Token-bucket throttling |
| `notification.rate-limit.default-rule.*` | — | Global bucket shape |
| `notification.rate-limit.by-channel.<name>.*` | — | Per-channel default (DD-23) |
| `notification.rate-limit.overrides[*]` | — | Per `(tenant, caller, channel)` overrides |
| `notification.retry.enabled` | `false` | Synchronous retry on transient failures |
| `notification.retry.max-attempts` | `3` | Global attempts (incl. first try) |
| `notification.retry.by-channel.<name>.*` | — | Per-channel override (DD-23) |
| `notification.dead-letter.enabled` | `false` | DLQ recording |
| `notification.dead-letter.max-entries` | `1000` | In-memory bound |
| `notification.webhooks.enabled` | `false` | `/webhooks/*` surface |
| `notification.webhooks.twilio.enabled` | `false` | Twilio status callbacks |
| `notification.webhooks.twilio.auth-token` | — | Required when verification on |
| `notification.webhooks.ses.enabled` | `false` | SES delivery callbacks via SNS |
| `notification.webhooks.ses.topic-arn` | — | Defense-in-depth topic match |
| `notification.delivery-events.enabled` | `false` | Persistent delivery event store |
| `notification.delivery-events.max-entries` | `5000` | In-memory bound |
| `notification.redis.key-prefix` | `notification-svc` | Namespace on shared Redis |
| `notification.redis.idempotency.enabled` | `false` | Redis-backed idempotency |
| `notification.redis.rate-limit.enabled` | `false` | Redis-backed rate limit |
| `notification.redis.dead-letter.enabled` | `false` | Redis-backed DLQ |
| `notification.redis.dead-letter.max-entries` | `1000` | Redis list cap |
| `notification.redis.delivery-events.enabled` | `false` | Redis-backed delivery events |
| `notification.redis.delivery-events.max-entries` | `10000` | Redis list cap |
| `notification.health.dlq-near-full-percent` | `80` | DLQ → `OUT_OF_SERVICE` threshold |

---

## See also

- [README](../README.md) — landing page, quick start, REST/Kafka examples
- [ARCHITECTURE.md](./ARCHITECTURE.md) — system overview, send-path lifecycle, SPI catalogue
- [`docs/design-decisions/`](./design-decisions/) — the 23 DDs with full rationale
- [CHANGELOG.md](../CHANGELOG.md) — version history
