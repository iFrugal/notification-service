# Notification Service

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=iFrugal_notification-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=iFrugal_notification-service)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=iFrugal_notification-service&metric=coverage)](https://sonarcloud.io/summary/new_code?id=iFrugal_notification-service)
[![SonarCloud](https://sonarcloud.io/images/project_badges/sonarcloud-white.svg)](https://sonarcloud.io/summary/new_code?id=iFrugal_notification-service)
[![CI](https://github.com/iFrugal/notification-service/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/iFrugal/notification-service/actions/workflows/ci.yml)
[![CodeQL](https://github.com/iFrugal/notification-service/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/iFrugal/notification-service/actions/workflows/codeql.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)

A multi-tenant notification service with pluggable providers.
Email (SMTP, AWS SES, Azure Communication Services) and SMS (Twilio) ship with built-in providers; WhatsApp and Push exist as provider SPIs, and their built-in providers are planned (see [Planned providers](#planned-providers)).
Can be used as a **Spring Boot Starter** (library) or deployed as a **standalone Docker container**.

> ### 📋 **[Feature Matrix → `docs/FEATURE_MATRIX.md`](docs/FEATURE_MATRIX.md)**
>
> **For integrators:** every feature, which JAR to add, which property to flip, side-by-side. Three worked examples (single-pod email-only → SaaS multi-channel → multi-pod distributed). Start there if you're asking *"what do I actually need to enable X?"*

## Table of Contents

- **[📋 Feature Matrix](docs/FEATURE_MATRIX.md)** — what to add and configure per feature
- [Features](#features)
- [Architecture](#architecture)
- [Modules](#modules)
  - [Planned providers](#planned-providers)
- [Quick Start](#quick-start)
  - [As a Spring Boot Starter](#as-a-spring-boot-starter)
  - [As a Standalone Service](#as-a-standalone-service)
- [Configuration](#configuration)
  - [Tenant Configuration](#tenant-configuration)
  - [Channel & Provider Configuration](#channel--provider-configuration)
  - [Email providers](#email-providers)
  - [Default Provider Selection](#default-provider-selection)
  - [Store selection](#store-selection)
  - [Template Configuration](#template-configuration)
- [REST API](#rest-api)
  - [Send Notification](#send-notification)
  - [Send Batch](#send-batch)
  - [Admin Endpoints](#admin-endpoints)
- [Kafka Integration](#kafka-integration)
- [Adding Custom Providers](#adding-custom-providers)
  - [How providers are resolved](#how-providers-are-resolved)
  - [Option 1: Spring Bean](#option-1-spring-bean)
  - [Option 2: FQCN (Reflection)](#option-2-fqcn-reflection)
- [Templates](#templates)
- [Multi-Tenancy](#multi-tenancy)
- [Audit](#audit)
- [Distributed deployment (Redis backends)](#distributed-deployment-redis-backends)
- [JDBC store](#jdbc-store)
- [Native image](#native-image)
- [Design Decisions](#design-decisions)
- [Dependencies](#dependencies)
- [Building](#building)
- [License](#license)

---

## Features

- **Multi-Channel Support**: Email and SMS with built-in providers; WhatsApp and Push as SPI extension points (`WhatsAppProvider`, `PushProvider`) with built-in providers planned
- **Multiple Providers per Channel**: SMTP, AWS SES and Azure Communication Services Email ([`email-provider-acs`](notification-channels/notification-channel-email/email-provider-acs/README.md)) for email, Twilio for SMS; AWS SNS, WhatsApp (Twilio, Meta) and Push (FCM, APNs) providers are planned
- **Multi-Tenancy**: Tenant-specific configurations via `X-Tenant-Id` header
- **Caller Identity**: Optional `X-Service-Id` header — feeds idempotency dedup, audit, and an opt-in caller registry (DD-11)
- **Idempotency**: Optional `idempotencyKey` field with pluggable store (DD-10)
- **Rate Limiting**: Opt-in token-bucket throttle per `(tenant, caller, channel)` with `429 + Retry-After` (DD-12); per-channel default rules let operators bound SMS tighter than email without enumerating overrides (DD-23)
- **Retries + DLQ**: Opt-in synchronous retry with classified failures (TRANSIENT/PERMANENT/UNKNOWN) and exponential backoff with jitter; pluggable dead-letter store SPI (DD-13); operator replay endpoint with `replayOf` chain (DD-15); per-channel `byChannel` retry rule overrides (DD-23)
- **OpenAPI / Swagger**: Self-documenting via `/v3/api-docs` + `/swagger-ui` (springdoc, optional in library mode); schema published as a CI build artifact for client codegen
- **Distributed mode**: Optional `notification-redis` module providing Redis-backed implementations of the idempotency, rate-limit, DLQ and delivery-event SPIs for multi-pod deployments (DD-14), or `notification-store-jdbc` for PostgreSQL-backed idempotency, DLQ and delivery events; pick the family with `notification.store.type`
- **Webhook delivery callbacks**: Opt-in `/webhooks/{provider}/...` surface ingests Twilio status (HMAC-SHA1) and SES via SNS (X.509) callbacks; parsed events flow to a `DeliveryEventListener` SPI (DD-16)
- **Delivery event store**: Opt-in bounded `DeliveryEventStore` SPI for `GET /admin/delivery-events` queryable history; in-memory Caffeine default + Redis backend (DD-17); `?requestId=…` joins via audit so operators query by the id they already know (DD-18)
- **Observability**: Per-SPI actuator health indicators with near-full DLQ alerting via `OUT_OF_SERVICE` (DD-21); Micrometer metrics across the send path (sends/retries/rate-limit/DLQ/delivery) for Prometheus or any Micrometer-compatible registry (DD-22)
- **Template Engine**: FreeMarker templates with tenant-specific overrides
- **Pluggable Providers**: Add custom providers via Spring Bean or FQCN
- **Dual Deployment**: Use as library (starter) or standalone Docker service
- **REST & Kafka**: Accept notifications via REST API or Kafka consumer
- **Audit Trail**: `NotificationAuditService` SPI with a no-op default (`NoOpAuditService`); no persistence backend ships, so register your own bean to keep history
- **Fail-Fast Validation**: All providers validated at startup

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Notification Service                         │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │  REST API   │  │    Kafka    │  │   Programmatic API      │  │
│  │ Controller  │  │  Listener   │  │  (NotificationService)  │  │
│  └──────┬──────┘  └──────┬──────┘  └────────────┬────────────┘  │
│         │                │                      │                │
│         └────────────────┼──────────────────────┘                │
│                          ▼                                       │
│              ┌───────────────────────┐                          │
│              │  NotificationService  │                          │
│              │    (Core Logic)       │                          │
│              └───────────┬───────────┘                          │
│                          │                                       │
│         ┌────────────────┼────────────────┐                     │
│         ▼                ▼                ▼                     │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             │
│  │  Template   │  │  Provider   │  │   Audit     │             │
│  │   Engine    │  │  Registry   │  │  Service    │             │
│  └─────────────┘  └──────┬──────┘  └─────────────┘             │
│                          │                                       │
│    ┌─────────┬───────────┼───────────┐                         │
│    ▼         ▼           ▼           ▼                         │
│ ┌──────┐ ┌──────┐   ┌────────┐  ┌────────┐                     │
│ │ SMTP │ │ SES  │   │ Twilio │  │ Custom │                     │
│ └──────┘ └──────┘   └────────┘  └────────┘                     │
└─────────────────────────────────────────────────────────────────┘
```

`Custom` is any provider you register against a channel SPI (see [Adding Custom Providers](#adding-custom-providers)).
WhatsApp and Push have no built-in provider yet; see [Planned providers](#planned-providers).

---

## Modules

| Module | Description |
|--------|-------------|
| `notification-api` | Core interfaces, DTOs, and exceptions |
| `notification-core` | Service implementation, provider registry, template engine, in-memory stores |
| `notification-rest` | REST controllers, filters and webhooks; opt-in with `notification.rest.enabled=true` |
| `notification-kafka` | Kafka consumer for async notifications |
| `notification-redis` | Redis-backed stores and rate limiter (`notification.store.type=redis`) |
| `notification-store-jdbc` | PostgreSQL-backed stores over plain SQL (`notification.store.type=jdbc`) |
| `notification-channels/*` | Built-in providers: `email-provider-smtp`, `email-provider-ses`, `email-provider-acs`, `sms-provider-twilio` |
| `notification-spring-boot-starter` | The dependency to add in library mode; every module brings its own auto-configuration |
| `notification-server` | Standalone application with Dockerfile |

### Planned providers

The channel SPIs `SmsProvider`, `WhatsAppProvider` and `PushProvider` live in `notification-api` and exist today.
You can send on any of these channels now by implementing the SPI yourself (see [Adding Custom Providers](#adding-custom-providers)).
The built-in providers below are planned and have no published artifact:

| Channel | Provider | Status |
|---------|----------|--------|
| SMS | AWS SNS | Planned |
| WhatsApp | Twilio | Planned |
| WhatsApp | Meta WhatsApp Cloud API | Planned |
| Push | Firebase Cloud Messaging (FCM) | Planned |
| Push | Apple Push Notification service (APNs) | Planned |

Planned order: FCM (HTTP v1 API) first, then the Meta WhatsApp Cloud API with a signed webhook.
The remaining providers are not scheduled yet.

---

## Quick Start

### As a Spring Boot Starter

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>notification-spring-boot-starter</artifactId>
    <version>1.1.0</version>
</dependency>

<!-- Add providers you need -->
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>email-provider-smtp</artifactId>
    <version>1.1.0</version>
</dependency>

<!-- Only if you want the HTTP API (see notification.rest.enabled below) -->
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>notification-rest</artifactId>
    <version>1.1.0</version>
</dependency>
```

Each module registers its own beans through Spring Boot auto-configuration, so adding the jar is all the wiring there is.
The starter does not component-scan `com.lazydevs.notification`; if you put your own beans under that package, register them yourself.

Configure in `application.yml`:

```yaml
notification:
  default-tenant: default
  rest:
    enabled: true          # REST is off by default since 1.1.0; omit for a library-only host
  store:
    type: memory           # default; redis or jdbc for shared state (see Store selection)
  tenants:
    default:
      channels:
        email:
          enabled: true
          providers:
            smtp:
              properties:
                host: smtp.gmail.com
                port: 587
                username: ${SMTP_USER}
                password: ${SMTP_PASSWORD}
```

Inject and use:

```java
@Autowired
private NotificationService notificationService;

public void sendWelcomeEmail(String email, String name) {
    NotificationRequest request = NotificationRequest.builder()
        .channel(Channel.EMAIL)
        .notificationType("WELCOME")
        .recipient(EmailRecipient.builder()
            .to(List.of(email))
            .build())
        .templateData(Map.of("name", name))
        .build();

    NotificationResponse response = notificationService.send(request);
}
```

The REST API, its tenant and caller filters and its exception handler are off by default since 1.1.0.
Set `notification.rest.enabled=true` and add `notification-rest` to expose them; the filters then apply only under `notification.rest.base-path` (default `/api/v1`), and webhooks additionally need `notification.webhooks.enabled=true`.
The standalone server sets `notification.rest.enabled=true` in its own `application.yml`.

### As a Standalone Service

**Using Docker:**

```bash
docker run -d \
  -p 8080:8080 \
  -v /path/to/application.yml:/config/application.yml \
  -e SPRING_CONFIG_LOCATION=/config/application.yml \
  ifrugal/notification-service:latest
```

**Using Docker Compose:**

```yaml
version: '3.8'
services:
  notification-service:
    image: ifrugal/notification-service:latest
    ports:
      - "8080:8080"
    volumes:
      - ./application.yml:/config/application.yml
    environment:
      - SPRING_CONFIG_LOCATION=/config/application.yml
      - SMTP_HOST=smtp.gmail.com
      - SMTP_USER=your-email@gmail.com
      - SMTP_PASSWORD=your-app-password
```

---

## Configuration

### Tenant Configuration

Each tenant can have its own channel and provider configurations:

```yaml
notification:
  default-tenant: default

  tenants:
    default:
      channels:
        email:
          enabled: true
          config:
            from-address: noreply@example.com
          providers:
            smtp:
              properties:
                host: smtp.gmail.com

    acme-corp:
      channels:
        email:
          enabled: true
          config:
            from-address: notifications@acme.com
          providers:
            ses:
              default: true
              properties:
                region: us-west-2
```

### Channel & Provider Configuration

```yaml
notification:
  tenants:
    default:
      channels:
        email:
          enabled: true
          config:                    # Channel-level config (shared by all providers)
            from-address: noreply@example.com
            from-name: My App
          providers:
            smtp:                    # Provider name
              default: true          # Mark as default (optional)
              beanName: mySmtpBean   # Use Spring bean (optional)
              fqcn: com.example.MyProvider  # Use class (optional)
              properties:            # Provider-specific config
                host: smtp.gmail.com
                port: 587
```

### Email providers

| Provider name | Artifact | Notes |
|---------------|----------|-------|
| `smtp` | `email-provider-smtp` | Jakarta Mail over any SMTP relay |
| `ses` | `email-provider-ses` | AWS SES v2 |
| `acs` | `email-provider-acs` | Azure Communication Services Email; see the [module README](notification-channels/notification-channel-email/email-provider-acs/README.md) |

ACS is configured per tenant like every other provider; there is no global `notification.email.acs.*` namespace.

```yaml
notification:
  tenants:
    default:
      channels:
        email:
          enabled: true
          config:
            from-address: DoNotReply@notify.example.com   # used when 'sender' is absent
          providers:
            acs:
              properties:
                connection-string: ${ACS_CONNECTION_STRING}
                sender: DoNotReply@notify.example.com
```

| Key | Default | Description |
|-----|---------|-------------|
| `connection-string` | - | ACS connection string (`endpoint=https://<resource>.communication.azure.com/;accesskey=<key>`). Wins over `endpoint` when both are set. |
| `endpoint` | - | ACS resource endpoint, used together with `credential`. |
| `credential` | - | `default` for `DefaultAzureCredential` (needs `azure-identity`), otherwise the name of a `TokenCredential` bean. Blank with `endpoint` uses the application's only `TokenCredential` bean. |
| `sender` | channel `from-address` | Sender address. It must be a MailFrom address of a domain connected to the ACS resource. Required. |
| `reply-to` | - | Default reply-to addresses, comma-separated or a YAML list. The recipient's own `replyTo` overrides it. |
| `send-mode` | `wait` | `wait` polls until ACS reports a final status. `submit` returns once ACS accepted the message. |
| `wait-timeout` | `PT60S` | ISO-8601 upper bound for `wait` mode. |
| `sdk-retries` | `0` | Retries performed inside the Azure SDK. Keep `0` so the library's `RetryExecutor` is the only retry layer. |
| `user-engagement-tracking-disabled` | ACS resource setting | `true` disables open and click tracking for messages from this tenant. |

ACS authentication, pick one:

1. **Connection string:** set `connection-string`; no extra dependency.
2. **Managed identity, workload identity, environment or Azure CLI credentials:** set `endpoint` and `credential: default`, and add `com.azure:azure-identity`.
3. **Your own `TokenCredential` bean:** set `endpoint` and `credential: <bean name>`, or leave `credential` blank when the application has exactly one such bean.

### Default Provider Selection

When a request doesn't specify a provider:

| Scenario | Behavior |
|----------|----------|
| Single provider configured | Auto-selected (no config needed) |
| Multiple providers, one with `default: true` | That provider is used |
| Multiple providers, none with `default: true` | Error: provider required in request |
| Multiple providers, 2+ with `default: true` | Startup failure |

### Store selection

Idempotency, rate limiting, the dead-letter queue and delivery events each keep state in a store.
`notification.store.type` picks the store family for all of them: `memory` (default, per JVM, in `notification-core`), `redis` (needs `notification-redis`) or `jdbc` (needs `notification-store-jdbc`).

```yaml
notification:
  store:
    type: jdbc
  idempotency:
    enabled: true          # default
  dead-letter:
    enabled: true
```

For each feature the store resolves in this order:

1. The feature flag is the master switch: `notification.idempotency.enabled` (default `true`), `notification.rate-limit.enabled`, `notification.dead-letter.enabled`, `notification.delivery-events.enabled` (default `false`).
   A feature that is off gets no store, whatever else is set.
2. An explicit `notification.redis.<feature>.enabled` overrides the family for that feature only: `true` selects Redis, `false` selects memory.
3. Otherwise `notification.store.type` decides.

The JDBC family has no rate limiter, so with `store.type=jdbc` rate limiting stays on the in-memory Bucket4j limiter unless `notification.redis.rate-limit.enabled=true`.
A bean of the store SPI that you define yourself always wins over the selected family.

If an enabled feature resolves to a family whose module is not on the classpath, startup fails instead of silently falling back to memory:

```text
Store family 'jdbc' is selected for dead-letter, but notification-store-jdbc is not on the classpath. Add the Maven dependency com.github.ifrugal:notification-store-jdbc (same version as notification-core).
```

`notification.redis.enabled` was never read and is no longer bound; startup logs a warning when it is set.

### Template Configuration

```yaml
notification:
  template:
    base-path: classpath:/templates/
    cache-enabled: true
    cache-ttl-seconds: 3600
    cache-max-size: 1000
    auto-escape: false
```

| Property | Default | Meaning |
|---|---|---|
| `base-path` | `classpath:/templates/` | Root of the template tree; see [Templates](#templates) for the resolution order. |
| `cache-enabled` | `true` | Cache loaded template sources. |
| `cache-ttl-seconds` | `3600` | Seconds before a cached template is read again; `0` or negative never expires. Read since 1.1.2 (it was bound but ignored before). |
| `cache-max-size` | `1000` | Maximum cached templates across all tenants; `0` or negative is unbounded. Since 1.1.2. |
| `auto-escape` | `false` | HTML-escape interpolated values in HTML email bodies (see [Auto-escaping](#auto-escaping)). Since 1.1.2. |

`POST /api/v1/admin/cache/templates/clear` still clears the cache for one tenant (`?tenantId=acme`) or for all tenants.

---

## REST API

### Live API documentation

The standalone server exposes its OpenAPI 3.1 schema and an interactive Swagger UI out of the box (springdoc, Phase 9).
In library mode springdoc is an optional dependency of `notification-rest` since 1.1.0: add `org.springdoc:springdoc-openapi-starter-webmvc-ui` to your application to get the same endpoints.

| Path | What |
|------|------|
| `/v3/api-docs` | OpenAPI 3.1 schema as JSON. The build also persists it to `notification-server/target/openapi.json` for CI to upload as a release artifact. |
| `/swagger-ui.html` (redirects to `/swagger-ui/index.html`) | Interactive UI — try the endpoints from a browser. |

Disable in production with:

```yaml
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

The schema includes all the cross-cutting headers documented above
(`X-Tenant-Id`, `X-Service-Id`, `X-Idempotent-Replay`, `Retry-After`),
the four DD-specific status codes (`409 / 429 / 403 / 503`), and the
admin endpoints. A regression test (`OpenApiSmokeTest`) asserts that
`/v3/api-docs` returns a valid schema with the expected paths so a
future Spring Boot or springdoc upgrade can't silently break client
codegen.

### Send Notification

```http
POST /api/v1/notifications
X-Tenant-Id: default
Content-Type: application/json

{
  "channel": "EMAIL",
  "notificationType": "ORDER_CONFIRMATION",
  "recipient": {
    "type": "email",
    "to": ["customer@example.com"],
    "cc": ["support@example.com"]
  },
  "templateData": {
    "orderId": "ORD-12345",
    "customerName": "John Doe",
    "items": [
      {"name": "Product A", "qty": 2, "price": 29.99}
    ]
  },
  "provider": "smtp",
  "priority": "HIGH",
  "correlationId": "ext-ref-123"
}
```

**Response:**

```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "correlationId": "ext-ref-123",
  "tenantId": "default",
  "channel": "EMAIL",
  "provider": "smtp",
  "status": "SENT",
  "providerMessageId": "msg-abc123",
  "receivedAt": "2024-01-15T10:30:00Z",
  "processedAt": "2024-01-15T10:30:01Z",
  "sentAt": "2024-01-15T10:30:01Z"
}
```

### Idempotency

To make retries safe, include an opaque `idempotencyKey` (max 255
characters) in the request body. The service deduplicates against an
in-memory store, scoped per `(tenantId, callerId, idempotencyKey)`,
with a configurable TTL — default 24 hours.

```json
{
  "idempotencyKey": "order-12345-confirmation",
  "channel": "EMAIL",
  "notificationType": "ORDER_CONFIRMATION",
  "recipient": { "to": ["customer@example.com"] },
  "templateData": { "orderId": "12345" }
}
```

**Behaviour for duplicate keys within TTL:**

| Prior state | Status code | Body | Header |
|---|---|---|---|
| No prior record | 200 | Fresh response (`status: "SENT"` etc.) | — |
| Prior request still **in-flight** | 409 | `{ "notificationId": "<original>", "status": "IN_PROGRESS" }` | — |
| Prior request **completed successfully** (`SENT` / `DELIVERED` / `ACCEPTED`) | 200 | The original response, replayed verbatim | `X-Idempotent-Replay: true` |
| Prior request **failed permanently** (`FAILED` / `REJECTED`) | Treated as fresh — proceeds to a new provider call | New response | — |

The `X-Idempotent-Replay` header lets callers with side-effect-on-success
flows (e.g. "after the email sends, mark the ticket Resolved")
distinguish a real send from a cache replay without parsing timestamps
or comparing `requestId`.

Disable idempotency entirely with:

```yaml
notification:
  idempotency:
    enabled: false
```

Tune TTL or cache bound:

```yaml
notification:
  idempotency:
    ttl: PT24H        # ISO-8601 duration
    max-entries: 100000
```

See [DD-10](docs/design-decisions/10-idempotency.md) for the design
rationale, semantics, and the storage-SPI shape for replacing the
default in-memory store with Redis.

### Caller Identity

Identify the calling service with the optional `X-Service-Id` header (max
128 characters). The value flows into the response, the audit record, and
the idempotency dedup tuple — closing the cross-service collision risk
described in [DD-11](docs/design-decisions/11-caller-identity.md).

```http
POST /api/v1/notifications
X-Tenant-Id: default
X-Service-Id: billing-svc
Content-Type: application/json

{ ... }
```

The header is optional. Requests that omit it continue to work exactly as
before — the only effect is that the idempotency scope reduces to
`(tenantId, null, idempotencyKey)`.

Header names are matched case-insensitively, as HTTP requires: `X-Service-Id`, `x-service-id` and `X-SERVICE-ID` are the same header, and likewise for `X-Tenant-Id`.

**Caller registry (optional, off by default).** When operators want to
track or restrict which services may call the notification API, populate
the registry:

```yaml
notification:
  caller-registry:
    enabled: true
    strict: false              # set true to reject unknown caller-ids
    known-services:
      - billing-svc
      - marketing-svc
      - account-svc
```

Behaviour matrix:

| `enabled` | `strict` | Unknown `X-Service-Id` | Missing `X-Service-Id` |
|-----------|----------|------------------------|------------------------|
| `false`   | _n/a_    | accepted, no log       | accepted, `callerId = null` |
| `true`    | `false`  | accepted, WARN log     | accepted, `callerId = null` |
| `true`    | `true`   | **rejected with HTTP 403** | accepted, `callerId = null` |

The current registry state is exposed at `GET /api/v1/admin/caller-registry`:

```json
{
  "enabled": true,
  "strict": false,
  "knownServices": ["billing-svc", "marketing-svc", "account-svc"]
}
```

### Rate Limiting

Throttle requests per `(tenant, caller, channel)` triple with a
token-bucket model. Off by default — see
[DD-12](docs/design-decisions/12-rate-limiting.md).

```yaml
notification:
  rate-limit:
    enabled: true
    default-rule:
      capacity: 200             # bucket size = burst tolerance
      refill-tokens: 100        # tokens added per refill period
      refill-period: PT1S       # ISO-8601 duration
    overrides:
      - tenant: acme            # required
        caller: billing-svc     # optional — omit for tenant-wide
        channel: sms            # optional — omit for all channels
        capacity: 50
        refill-tokens: 10
        refill-period: PT1S
      - tenant: acme
        caller: marketing-svc
        capacity: 1000
        refill-tokens: 1000
        refill-period: PT1S
```

**Match precedence (most specific wins):**
`(tenant, caller, channel)` → `(tenant, caller)` → `(tenant)` → `default`.

**On rejection (REST):**

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 3
Content-Type: application/json

{
  "error": "RATE_LIMIT_EXCEEDED",
  "retryAfterSeconds": 3,
  "message": "Rate limit exceeded for tenant=acme, caller=billing-svc, channel=email (retry after 3s)"
}
```

`Retry-After` is rounded up to whole seconds per RFC 7231.

**On rejection (Kafka):** the message is logged at WARN level and the
offset is committed. At-least-once requeue would amplify the pressure
the limiter is trying to relieve.

### Retries + Dead-Letter

Off by default. When enabled, transient provider failures retry with
exponential backoff + jitter, and permanently-failed (or
retry-exhausted) notifications land in a configurable dead-letter
store. See [DD-13](docs/design-decisions/13-retries-and-dlq.md).

```yaml
notification:
  retry:
    enabled: true
    max-attempts: 3            # total attempts including the first
    initial-delay: PT1S
    multiplier: 2.0
    max-delay: PT30S
    jitter: 0.5                # 0..1, fraction of delay randomised ±

  dead-letter:
    enabled: true
    max-entries: 1000          # in-memory bound; older entries fall off
```

**Failure classification:** providers mark a `SendResult` failure as
`TRANSIENT`, `PERMANENT`, or `UNKNOWN`. The default `RetryPredicate`
retries TRANSIENT and UNKNOWN, skips PERMANENT — operators can plug a
custom predicate as a Spring bean.

The bundled providers classify their native errors via
`com.lazydevs.notification.api.model.FailureTypes`:

| Provider | TRANSIENT (retry) | PERMANENT (skip retry, go to DLQ) |
|---|---|---|
| **SMTP** (Jakarta Mail) | I/O timeouts, connection errors, generic `MessagingException` (server 4xx/5xx replies) | `AuthenticationFailedException`, `AddressException`, `SendFailedException` with all-invalid recipients |
| **AWS SES v2** | `SdkClientException` (network), HTTP 5xx / 408 / 425 / 429 from SES | `AccountSuspendedException`, `SendingPausedException`, `MailFromDomainNotVerifiedException`, `MessageRejectedException`, `BadRequestException`, other 4xx |
| **Twilio SMS** | HTTP 5xx / 408 / 425 / 429, null status (network failure pre-response) | `AuthenticationException`, other HTTP 4xx (e.g. error code `21211 Invalid To Number` arrives as 400) |

Anything outside these tables is `UNKNOWN` — the default predicate
treats UNKNOWN as retry-worthy (best-effort) so the classifier can be
expanded incrementally without changing behaviour. Custom predicates
can opt out of retrying UNKNOWN if operators want strict-only retries.

**Retry order in the pipeline:**

```text
enrichRequest → idempotency-replay short-circuit → rate-limit check
              → markInProgress → [retry loop: render → provider.send]
              → markComplete → if failed: push to DLQ → audit
```

Two invariants worth calling out:

- **Rate-limit token consumed once per logical send**, not per retry —
  a transient blip doesn't drain the caller's bucket.
- **Idempotency lock held for the entire retry window** — concurrent
  duplicates see the same `requestId` and 409, exactly as DD-10
  specifies.

**On the Kafka path**, retries happen on the consumer thread before
the offset is committed. Operators with strict throughput SLAs should
keep `max-attempts` low and rely on the DLQ for terminal failures.

**Admin endpoint:** `GET /api/v1/admin/dead-letter` returns the
configured cap + recent entries (most recent first):

```json
{
  "enabled": true,
  "maxEntries": 1000,
  "size": 12,
  "entries": [
    {
      "timestamp": "2026-04-28T10:42:01Z",
      "tenantId": "acme",
      "callerId": "billing-svc",
      "channel": "EMAIL",
      "requestId": "req-abc-123",
      "attempts": 3,
      "failureType": "TRANSIENT",
      "errorCode": "PROVIDER_TIMEOUT",
      "errorMessage": "smtp 421 — try again later"
    }
  ]
}
```

The admin response intentionally omits the request payload (template
data may carry PII). To re-send a dead-lettered notification by id,
use the **replay endpoint** (DD-15) below.

When the DLQ is disabled (`notification.dead-letter.enabled=false`)
the endpoint returns **HTTP 503 Service Unavailable** with a small
explanatory body — the endpoint is meaningfully disabled, not just
empty:

```json
{
  "enabled": false,
  "message": "Dead-letter store is disabled. Enable with notification.dead-letter.enabled=true."
}
```

#### Replaying a dead-lettered notification (DD-15)

```http
POST /api/v1/admin/dead-letter/{requestId}/replay?tenantId=acme
```

The replay path first **claims** the entry by `(tenantId, requestId)` for `notification.dead-letter.replay-lease` (default `PT5M`), so no other replay, on this or another replica, can take it meanwhile.
It then builds a fresh request from the captured payload (new `requestId`, new `idempotencyKey`, `replayOf` set to the original) and re-submits it through `NotificationService.send()`.
On success the original entry is **removed** from the DLQ, which acknowledges the claim; the chain stays reconstructable through the audit log via `replayOf`.
On failure the entry stays and its claim is **released**, so a later attempt can take it straight away.
If the replica dies mid-replay, the claim simply lapses when the lease ends.

```yaml
notification:
  dead-letter:
    enabled: true
    replay-lease: PT5M     # longer than your slowest send, including retries
```

The JDBC store enforces claims across replicas with `FOR UPDATE SKIP LOCKED`.
The in-memory and Redis stores keep the claim methods' lock-free defaults, so with them two concurrent replays of the same entry can still both send it.

Successful replay (`200 OK`):

```json
{
  "originalRequestId": "req-abc-123",
  "newRequestId": "req-def-456",
  "replayOf": "req-abc-123",
  "tenantId": "acme",
  "callerId": "billing-svc",
  "channel": "EMAIL",
  "status": "SENT",
  "removedFromDlq": true,
  "message": "Replay submitted; entry removed from DLQ on successful send."
}
```

Status codes: `404` when the request id isn't in the DLQ; `409` with `"status": "CLAIMED"` when another replay currently holds the entry (nothing is sent); `502` when the replay reaches a provider but fails again (entry kept and released); `500` when the send throws before reaching a provider (entry kept and released); `503` when the DLQ is disabled.

**Bulk replay** (`POST /api/v1/admin/dead-letter/replay-batch?tenantId=acme&limit=100`, DD-19) claims up to `limit` entries of the tenant in one call (capped at 1000), so batches running on several replicas at once work on disjoint entries.
Each claimed entry is replayed, then removed on success or released on failure.
Entries of the tenant that another replay holds are skipped and listed with `"status": "CLAIMED"`; they count under `claimed`, not under `stillDeadLettered`.
`?dryRun=true` previews from the DLQ snapshot without claiming, sending or removing anything.

```json
{
  "mode": "live",
  "tenantId": "acme",
  "requested": 3,
  "replayed": 1,
  "stillDeadLettered": 1,
  "claimed": 1,
  "entries": [
    {"originalRequestId": "req-1", "newRequestId": "req-9", "status": "SENT", "removedFromDlq": true},
    {"originalRequestId": "req-2", "newRequestId": "req-10", "status": "FAILED", "errorCode": "PROVIDER_TIMEOUT", "errorMessage": "smtp 421", "removedFromDlq": false},
    {"originalRequestId": "req-3", "status": "CLAIMED", "removedFromDlq": false}
  ],
  "message": "Bulk replay completed. Successful entries removed from DLQ; failed entries released and kept for inspection; entries claimed by another replay skipped."
}
```

The `replayOf` field on `NotificationRequest` is **server-set only** —
clients submitting it via `POST /api/v1/notifications` get a WARN log
and the value nulled before dispatch. Replays of replays still point
at the very first request id, so reconstruction is O(1).

Live state — configured rules + currently-tracked buckets — exposed at
`GET /api/v1/admin/rate-limit`:

```json
{
  "enabled": true,
  "default": {"capacity": 200, "refillTokens": 100, "refillPeriod": "PT1S"},
  "overrides": [
    {"tenant": "acme", "caller": "billing-svc", "channel": "sms",
     "capacity": 50, "refillTokens": 10, "refillPeriod": "PT1S"}
  ],
  "activeBuckets": [
    {"tenant": "acme", "caller": "billing-svc", "channel": "email",
     "availableTokens": 187}
  ]
}
```

### Send Batch

```http
POST /api/v1/notifications/batch
X-Tenant-Id: default
Content-Type: application/json

[
  { "channel": "EMAIL", ... },
  { "channel": "SMS", ... }
]
```

### Admin Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/admin/configuration` | GET | Get all tenant configurations |
| `/api/v1/admin/configuration/tenants/{id}` | GET | Get specific tenant config |
| `/api/v1/admin/configuration/tenants/{id}/channels/{ch}` | GET | Get channel config |
| `/api/v1/admin/caller-registry` | GET | Caller-registry state (DD-11) |
| `/api/v1/admin/rate-limit` | GET | Rate-limit config + live bucket snapshot (DD-12) |
| `/api/v1/admin/dead-letter` | GET | Recent retry-exhausted / permanent failures (DD-13) |
| `/api/v1/admin/dead-letter/{requestId}/replay` | POST | Re-submit a dead-lettered request with `replayOf` chain (DD-15) |
| `/api/v1/admin/dead-letter/replay-batch` | POST | Bulk replay DLQ entries for a tenant; supports `?dryRun=true` (DD-19) |
| `/api/v1/admin/audit/{requestId}` | GET | Look up a single audit record (DD-20) |
| `/api/v1/admin/audit/recent` | GET | Most-recent audit rows for a tenant (DD-20) |
| `/api/v1/admin/delivery-events` | GET | Recent provider delivery callbacks; filter by `providerName` + `providerMessageId` (DD-17) |
| `/api/v1/admin/health` | GET | Provider health status |
| `/api/v1/admin/cache/templates/clear` | POST | Clear template cache |

---

## Webhook delivery callbacks (DD-16)

Provider-side delivery callbacks (delivery / bounce / complaint) can
be ingested at `/webhooks/{provider}/...`. Off by default; each
provider section is independently toggleable.

```yaml
notification:
  webhooks:
    enabled: true                          # master switch
    base-path: /webhooks                   # joined with notification.rest.base-path
    twilio:
      enabled: true
      auth-token: ${TWILIO_AUTH_TOKEN}     # required when verification on
      signature-verification: true         # leave on in prod
    ses:
      enabled: true
      topic-arn: arn:aws:sns:us-east-1:0:my-topic   # defense-in-depth
      signature-verification: true
```

Configure the URL on the provider side:

| Provider | URL to register | Verification |
|----------|-----------------|--------------|
| Twilio | `https://your.host/api/v1/webhooks/twilio/status` | HMAC-SHA1 over URL + sorted form params |
| SES via SNS | `https://your.host/api/v1/webhooks/ses/sns` (subscribe the SNS topic to this URL) | SNS X.509 (SHA1withRSA / SHA256withRSA), cert URL host pinned to `*.amazonaws.com` |

Parsed events flow to every registered `DeliveryEventListener`. The
default `LoggingDeliveryEventListener` logs at INFO; replace with your
own listener bean to fan events into a real audit pipeline:

```java
@Component
public class MyDeliveryListener implements DeliveryEventListener {
    @Override
    public void onEvent(DeliveryEvent event) {
        // event.providerName(), event.providerMessageId(), event.status()
        // dedup on event.providerEventId() if you persist
    }
}
```

Status mapping:

| `DeliveryStatus` | Twilio | SES |
|-----------------|--------|-----|
| `DELIVERED` | `delivered` | `Delivery` |
| `BOUNCED` | `undelivered` | `Bounce` (Permanent) |
| `FAILED_AT_PROVIDER` | `failed` | `Bounce` (Transient) |
| `COMPLAINED` | (n/a) | `Complaint` |
| `UNKNOWN` | other | other |

**FCM is not currently supported** — Firebase Cloud Messaging doesn't
expose per-message webhook callbacks (BigQuery export only as of late
2025). The `notification.webhooks.fcm.*` namespace is reserved.

Failed signature verification returns `403 Forbidden`. A real provider
whose signing key rotated will see the 403 in their admin dashboard;
an attacker gets no information beyond "this endpoint exists." The
endpoint itself returns `404` when the provider isn't enabled, so
URLs you register but haven't toggled on remain inert.

For the SNS subscription handshake, the controller logs the
`SubscribeURL` from the `SubscriptionConfirmation` envelope —
operators confirm the subscription manually (paste into a browser or
the SNS console). We don't auto-fetch the URL because that's a
side-effecting GET we don't want firing on a forged envelope.

### Persisted delivery events (DD-17)

The DD-16 listener seam works well if you have your own audit pipeline.
For out-of-the-box queryability, enable the bounded
`DeliveryEventStore`:

```yaml
notification:
  delivery-events:
    enabled: true            # in-memory Caffeine, single pod
    max-entries: 5000        # tune for traffic volume
  redis:
    delivery-events:
      enabled: true          # share buffer across pods
      max-entries: 10000
```

The store *is* a `DeliveryEventListener` (via a default method on the
interface), so registering the bean automatically joins the listener
fan-out — no glue code. Composes with custom listeners: register your
own listener alongside, and both receive every event.

Query with `GET /admin/delivery-events`:

```http
GET /api/v1/admin/delivery-events?limit=100
GET /api/v1/admin/delivery-events?providerName=ses&providerMessageId=ses-msg-1
GET /api/v1/admin/delivery-events?requestId=req-abc-123    # DD-18 audit join
```

The raw provider attributes map (recipient phone numbers, email
addresses) is **excluded by default** — opt in with `?includeRaw=true`.
Returns `503` when the store is disabled (matches the DLQ admin
endpoint convention).

The `?requestId=…` form (DD-18) is the answer to "did *this* notification
deliver?" — the controller walks
`NotificationAuditService.findByRequestId` to recover the
`providerMessageId`, then queries the store. Returns one of four
shapes:

- `404` — no audit record (often because the `NoOpAuditService` is the
  default; wire a real audit backend to populate it)
- `200 auditState: incomplete` — the send hasn't completed yet
- `200 auditState: complete` with `entries: []` — send completed,
  no callbacks have arrived yet
- `200 auditState: complete` with events — full join result

`?requestId` wins over `?providerName + ?providerMessageId` when both
are supplied — the audit-supplied tuple is the stricter scope.

Both stores are inspection surfaces, not archives. For 90-day delivery
history wire a custom `DeliveryEventListener` that writes to S3 / a
data warehouse.

---

## Kafka Integration

Enable Kafka consumer:

```yaml
notification:
  kafka:
    enabled: true
    topic: notifications
    group-id: notification-service

spring:
  kafka:
    bootstrap-servers: localhost:9092
```

Message format (same as REST API body):

```json
{
  "channel": "EMAIL",
  "notificationType": "WELCOME",
  "recipient": { "type": "email", "to": ["user@example.com"] },
  "templateData": { "name": "John" }
}
```

**Headers** (mirroring the REST API):

- `X-Tenant-Id` — tenant identifier (DD-03). Defaults to
  `notification.default-tenant` when absent.
- `X-Service-Id` — calling-service identifier (DD-11). Optional. Stamped
  onto `request.callerId` so the Kafka path participates in the same
  idempotency dedup tuple `(tenantId, callerId, idempotencyKey)` and the
  same audit trail as REST. If the message body already sets `callerId`,
  the body wins.

The caller registry (`notification.caller-registry`) is consulted by the
REST admission filter only — Kafka deliveries skip the strict-mode 403
gate by design (publishers can't observe a 403 from a topic), so unknown
caller-ids on the Kafka path are accepted with a WARN log when the
registry is enabled.

---

## Adding Custom Providers

### How providers are resolved

For every provider configured under `notification.tenants.<tenant>.channels.<channel>.providers.<name>`, `ProviderRegistry` resolves an instance at startup, calls `configure(properties)` and `init()`, and caches it for that tenant:

1. `beanName` set: the Spring bean with that name.
2. Otherwise `fqcn` set: that class, instantiated reflectively (it does not need to be a Spring bean).
3. Otherwise the bean named `<name><Channel>Provider`, for example `smtpEmailProvider`, `acsEmailProvider` or `twilioSmsProvider`.

Built-in providers come from their module's auto-configuration, which registers that conventional bean as a **prototype**, so every tenant gets its own configured instance.
A bean you declare under the same name replaces the built-in one.
Your own provider can follow the same convention: a prototype bean named `postmarkEmailProvider` is picked up for the provider name `postmark` with no `beanName`.
Declare provider beans as prototypes; a singleton would be shared, and reconfigured, by every tenant that uses it.

A provider that is configured but cannot be resolved fails startup.
For a built-in whose module is missing, the message names the artifact to add:

```text
Built-in provider 'ses' for channel 'email' is not on the classpath. Add the Maven dependency com.github.ifrugal:email-provider-ses (same version as notification-core), or set 'beanName' or 'fqcn' under notification.tenants.default.channels.email.providers.ses.
```

### Option 1: Spring Bean

Create a prototype Spring bean implementing the provider interface:

```java
@Component("myCustomEmailProvider")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class MyCustomEmailProvider implements EmailProvider {

    @Override
    public String getProviderName() {
        return "my-custom";
    }

    @Override
    public void configure(Map<String, Object> config) {
        // Initialize with config
    }

    @Override
    public SendResult send(NotificationRequest request, RenderedContent content) {
        // Send email
        return SendResult.success("msg-id-123");
    }
}
```

Configure:

```yaml
providers:
  my-custom:
    beanName: myCustomEmailProvider
    properties:
      api-key: ${MY_API_KEY}
```

The starter does not component-scan, so the bean has to be in a package your application scans (or declared with `@Bean`).

### Option 2: FQCN (Reflection)

Create a class (doesn't need to be a Spring bean):

```java
public class MyEmailProvider implements EmailProvider {
    // ... implementation
}
```

Configure:

```yaml
providers:
  my-custom:
    fqcn: com.mycompany.MyEmailProvider
    properties:
      api-key: ${MY_API_KEY}
```

---

## Templates

Templates use FreeMarker and are located by convention:

```
templates/
├── default/                    # Fallback templates
│   ├── email/
│   │   ├── WELCOME.ftl
│   │   └── ORDER_CONFIRMATION.ftl
│   └── sms/
│       └── OTP.ftl
├── tenant-a/                   # Tenant-specific overrides
│   └── email/
│       └── WELCOME.ftl         # Overrides default for tenant-a
```

**Resolution order** (paths are relative to `notification.template.base-path`, `classpath:/templates/` by default):
1. `{base-path}{tenantId}/{channel}/{notificationType}.ftl`
2. `{base-path}default/{channel}/{notificationType}.ftl`

Up to 1.1.1 the engine inserted an extra `templates/` segment and looked for `classpath:/templates/templates/...`, so the shipped default templates were never found.
Since 1.1.2 it resolves the paths above.
For one release it still falls back to the old location (`{base-path}templates/{tenantId}/...`, then `{base-path}templates/default/...`) after the new one, and logs a WARN naming the file.
If you see that warning, move the file or append `templates/` to your `base-path`; the fallback is removed in 1.2.

**Security:** since 1.1.2, tenant ids, channel names and template ids must match `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$` and contain no `..`, `/` or `\`, so a request cannot read files outside `base-path`; an invalid id fails as template-not-found (HTTP 404) without echoing the value.

**Email sections:**

```text
[SUBJECT]Order #${orderId} confirmed[/SUBJECT]
[BODY]
<p>Hi ${customerName}, ...</p>
[/BODY]
[TEXT]
Hi ${customerName}, ...
[/TEXT]
```

- `[SUBJECT]` is the subject line; without `[BODY]`, everything after `[/SUBJECT]` is the body, and without any markers the whole output is the body.
- The body is sent as HTML when it contains `<html`, `<body`, `<div`, `<p>`, `<table`, `<br`, `<span`, `<a ` or `<!DOCTYPE` (case-insensitive), and as plain text otherwise.
- `[TEXT]` (optional, since 1.1.2) is a plain-text alternative: when present, the body is sent as the HTML part and the `[TEXT]` section as the text part of a multipart message.
  Without it nothing changes; deriving a text part automatically is planned for a later release.
- Only markers written in the template delimit sections.
  Since 1.1.2 a marker that arrives in template data (for example a name of `Bob[BODY]...[/BODY]`) is printed as text; before, it could replace the body or cut the subject short.

**Helpers** available in every template:

| Helper | Notes |
|---|---|
| `formatDate(value, pattern[, zone[, locale]])` | `value` may be any `java.time` value, `java.util.Date`, epoch milliseconds or an ISO-8601 string (date-time, instant or date); anything else is printed unchanged. Zone defaults to the JVM zone, locale to the JVM locale. |
| `formatDateTime(value, pattern[, zone[, locale]])` | Same values as `formatDate`. Zone defaults to `UTC`. |
| `formatCurrency(amount, currencyCode[, locale])` | Locale defaults to `en-US`. |
| `truncate(text, maxLength)` | Ends with `...` within the limit; a limit of 3 or less cuts without the ellipsis. |
| `capitalize(text)`, `escapeHtml(text)`, `urlEncode(text)` | A null value prints as an empty string. |
| `defaultValue(value, fallback)` | `fallback` when `value` is null or blank. |

`zone` is a zone id such as `Europe/Berlin` and `locale` a BCP-47 tag such as `de-DE`; pass `''` for the zone to set only the locale.
Values that carry their own offset (an `OffsetDateTime`, or a string such as `2026-10-02T09:15:00+05:30`) keep it unless you pass a zone.
A date-only value used with a pattern that needs a time is printed unchanged rather than failing the render.

#### Auto-escaping

With `notification.template.auto-escape: true`, an email body whose template source is HTML is rendered in FreeMarker's HTML output format, so every `${...}` in it is HTML-escaped.
`escapeHtml(...)` then returns HTML markup, so existing calls are not escaped twice, and `${trustedHtml?no_esc}` prints trusted markup as is.
The subject and the `[TEXT]` section are never escaped.
With the flag on, whether the body is HTML is decided from the template source, so data cannot turn a plain-text body into HTML.
A body with a `[TEXT]` section is the HTML part and is always escaped.
The engine wraps the body section of the template source in `<#outputformat "HTML">`, so a FreeMarker directive that opens inside the body and closes outside it (or the reverse) is a template error with the flag on.
String built-ins cannot be chained directly after `escapeHtml(...)` with the flag on (for example `escapeHtml(x)?upper_case`), because it returns markup rather than a string.

#### Known limitations

- `formatDate` defaults to the JVM zone and `formatDateTime` to UTC, and only `formatCurrency` defaults to a fixed locale (`en-US`); the defaults are kept for compatibility and will be unified in 1.2.
- The persistence-utils `TemplateEngine` that renders templates injects `file`, `eval` and `js` helpers into every template model; `file` reads any path the server process can read, so treat template authoring as a trusted, code-level capability.
  They will be removed when the engine owns its FreeMarker configuration in 1.2.
- `<#include>` and `<#import>` resolve against persistence-utils' own loaders (`classpath:/templates/` and `./templates`), not relative to the including template or to `base-path`, because templates are rendered from their source text.

**Example template (`templates/default/email/ORDER_CONFIRMATION.ftl`):**

```html
<html>
<body>
  <h1>Order Confirmation</h1>
  <p>Hi ${customerName},</p>
  <p>Your order #${orderId} has been confirmed.</p>

  <table>
    <#list items as item>
    <tr>
      <td>${item.name}</td>
      <td>${item.qty}</td>
      <td>$${item.price}</td>
    </tr>
    </#list>
  </table>
</body>
</html>
```

---

## Multi-Tenancy

Tenant is determined by:

1. **REST**: `X-Tenant-Id` header
2. **Kafka**: `X-Tenant-Id` message header
3. **Programmatic**: `request.setTenantId("tenant-a")`
4. **Fallback**: `notification.default-tenant` config

Each tenant can have:
- Different enabled channels
- Different providers per channel
- Different provider configurations
- Different templates (overrides)

---

## Audit

Audit is a `NotificationAuditService` SPI in `notification-core` with a no-op default (`NoOpAuditService`, which logs only).
No audit persistence backend ships with this project; to keep notification history, register your own `NotificationAuditService` bean.
The `notification.audit.*` properties below are bound for your bean to read; the shipped no-op ignores them.

```yaml
notification:
  audit:
    enabled: true
    store-request-payload: true
    store-response-payload: true
    retention-days: 90
    async: true
```

Audit records passed to the SPI include:
- Request/response details
- Status transitions
- Provider message IDs
- Error information
- Timestamps

---

## Distributed deployment (Redis backends)

The default `notification-core` implementations of idempotency, rate limiting, the DLQ and delivery events are in-memory: correct for single-pod deployments but wrong for multi-pod ones, where each pod gets its own state.
For multi-pod setups, pull the `notification-redis` module, which provides Redis-backed implementations of all four SPIs, or the [JDBC store](#jdbc-store) for everything except rate limiting.
See [DD-14](docs/design-decisions/14-distributed-redis-backends.md).

```xml
<dependency>
  <groupId>com.github.ifrugal</groupId>
  <artifactId>notification-redis</artifactId>
  <version>1.1.0</version>
</dependency>
```

Select Redis for every enabled feature with `notification.store.type=redis` (see [Store selection](#store-selection)).
The feature flags stay the master switch: Redis backs only the features that are enabled.

```yaml
notification:
  store:
    type: redis
  rate-limit:
    enabled: true
  dead-letter:
    enabled: true
  delivery-events:
    enabled: true
  redis:
    key-prefix: "notification-svc"     # avoids collisions on shared Redis
    dead-letter:
      max-entries: 1000
    delivery-events:
      max-entries: 10000

# Connection details — Spring Data Redis honours these
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
```

To migrate one feature at a time, leave `store.type` alone and set the per-feature override instead, for example `notification.redis.idempotency.enabled: true`.
An explicit `false` keeps that feature in memory even with `store.type=redis`.
Setting a per-feature Redis flag does not switch the feature itself on.

The Redis-backed beans use `@ConditionalOnMissingBean`, so a custom implementation (Hazelcast, DynamoDB, etc.) wins automatically.
`NotificationRedisAutoConfiguration` also needs Spring Data Redis and `bucket4j-redis` (Lettuce) on the classpath, which `notification-redis` brings in.

**Key namespacing.** All keys are prefixed with
`notification.redis.key-prefix` (default `notification-svc`). Multiple
services sharing one Redis instance should set distinct prefixes.

| Concern | Redis structure |
|---------|-----------------|
| Idempotency record | `String` at `<prefix>:idempotency:<tenant>:<caller>:<key>` (JSON, TTL via `EX`) |
| Rate-limit bucket | bucket4j-redis CAS state at `<prefix>:ratelimit:<tenant>:<caller>:<channel>` |
| Dead-letter list | `LIST` at `<prefix>:dlq` (LPUSH + LTRIM-bounded) |

Operators can read DLQ entries with `redis-cli LRANGE
<prefix>:dlq 0 -1` — entries are JSON, human-readable.

---

## JDBC store

`notification-store-jdbc` provides PostgreSQL-backed `IdempotencyStore`, `DeadLetterStore` and `DeliveryEventStore` implementations, written as plain SQL over Spring's `JdbcClient`.
There is no ORM and no migration tool, and the library never runs DDL.
Full details are in the [module README](notification-store-jdbc/README.md).

```xml
<dependency>
  <groupId>com.github.ifrugal</groupId>
  <artifactId>notification-store-jdbc</artifactId>
  <version>1.1.0</version>
</dependency>
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
</dependency>
```

```yaml
notification:
  store:
    type: jdbc
  dead-letter:
    enabled: true
  delivery-events:
    enabled: true
```

The host application supplies the `DataSource` and the JDBC driver.

| Property | Default | Meaning |
|---|---|---|
| `notification.store.jdbc.schema` | none | Schema that holds the tables. Unset uses the connection's search path. |
| `notification.store.jdbc.table-prefix` | `notification_` | Prefix for every table, constraint and index name. |
| `notification.store.jdbc.datasource-bean-name` | none | DataSource bean to use. Unset picks the single, or `@Primary`, DataSource. |
| `notification.store.jdbc.dialect` | `POSTGRESQL` | SQL dialect. Only PostgreSQL is implemented. |
| `notification.store.jdbc.dead-letter-retention` | `P30D` | Lifetime of a dead-letter row. |
| `notification.store.jdbc.delivery-event-retention` | `P30D` | Lifetime of a delivery-event row. |
| `notification.store.jdbc.purge.enabled` | `false` | Run the module's own scheduled purge of expired rows. |
| `notification.store.jdbc.purge.interval` | `PT1H` | Delay between purge runs. |
| `notification.store.jdbc.purge.batch-size` | `1000` | Maximum rows deleted per purge statement. |

- **Schema:** the host runs the migrations.
  The reference DDL (PostgreSQL 12+) ships in the jar as `db/postgresql/notification-store.sql`; `JdbcStoreSchema.postgresql(new JdbcStoreTables(schema, prefix)).ddl()` renders it for another schema or prefix.
- **Purge:** expired rows are invisible to every read at once; purging only reclaims space.
  Enable the module's daemon purge with `notification.store.jdbc.purge.enabled=true`, or call `JdbcStorePurger.purgeAll()` yourself.
  Purges on several replicas split the work through `SKIP LOCKED`.
- **Row-level security:** every table has a nullable `tenant_id` column, the application role needs only `SELECT, INSERT, UPDATE, DELETE`, and `JdbcStoreRlsIT` runs every store as a non-owner role under a tenant-isolation policy.
- **Dead-letter replay:** claims are leased with `FOR UPDATE SKIP LOCKED`, so replicas replaying the same tenant never send one entry twice.
- **No rate limiter:** rate limiting stays on the in-memory Bucket4j limiter, or on Redis with `notification.redis.rate-limit.enabled=true`.

---

## Native image

The core registers GraalVM reflection hints for the built-in provider classes and, at AOT build time, for every provider class your configuration names with `fqcn`.
`notification-store-jdbc` registers hints for the JSON it stores.
Native image support is not yet claimed or tested in CI; verify a native build of your own application before relying on it.

---

## Design Decisions

If you're integrating the service, start with the
**[📋 Feature Matrix](docs/FEATURE_MATRIX.md)** — every feature,
which JAR to add, which property to flip, plus three worked examples.

For the *why* behind the design, the **[Architecture overview](docs/ARCHITECTURE.md)**
walks through how the pieces fit together. The per-decision corpus
lives at [`docs/design-decisions/`](docs/design-decisions/) —
see the
[decision log](docs/design-decisions/00-decision-log.md) for the
complete index.

A version-by-version summary of what shipped when is in
[`CHANGELOG.md`](CHANGELOG.md).

---

## Dependencies

This project uses libraries from [all-about-persistence](https://github.com/iFrugal/all-about-persistence):

| Library | Usage |
|---------|-------|
| `persistence-utils` | `TemplateEngine`, `TenantContext`, `ClassUtils`, `ReflectionUtils` |
| `persistence-api` | Audit persistence interfaces |
| `app-building-commons` | `RequestContext`, `BasicRequestFilter`, `RESTException`, exception handling |

---

## Building

```bash
# Build all modules
mvn clean install

# Build Docker image
cd notification-server
mvn clean package jib:dockerBuild

# Run locally
mvn spring-boot:run -pl notification-server
```

---

## License

[MIT License](LICENSE)
