# Notification Service

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=iFrugal_notification-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=iFrugal_notification-service)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=iFrugal_notification-service&metric=coverage)](https://sonarcloud.io/summary/new_code?id=iFrugal_notification-service)
[![SonarCloud](https://sonarcloud.io/images/project_badges/sonarcloud-white.svg)](https://sonarcloud.io/summary/new_code?id=iFrugal_notification-service)
[![CI](https://github.com/iFrugal/notification-service/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/iFrugal/notification-service/actions/workflows/ci.yml)
[![CodeQL](https://github.com/iFrugal/notification-service/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/iFrugal/notification-service/actions/workflows/codeql.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)

A multi-tenant notification service supporting multiple channels (Email, SMS, WhatsApp, Push) with pluggable providers. Can be used as a **Spring Boot Starter** (library) or deployed as a **standalone Docker container**.

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [Modules](#modules)
- [Quick Start](#quick-start)
  - [As a Spring Boot Starter](#as-a-spring-boot-starter)
  - [As a Standalone Service](#as-a-standalone-service)
- [Configuration](#configuration)
  - [Tenant Configuration](#tenant-configuration)
  - [Channel & Provider Configuration](#channel--provider-configuration)
  - [Default Provider Selection](#default-provider-selection)
  - [Template Configuration](#template-configuration)
- [REST API](#rest-api)
  - [Send Notification](#send-notification)
  - [Send Batch](#send-batch)
  - [Admin Endpoints](#admin-endpoints)
- [Kafka Integration](#kafka-integration)
- [Adding Custom Providers](#adding-custom-providers)
  - [Option 1: Spring Bean](#option-1-spring-bean)
  - [Option 2: FQCN (Reflection)](#option-2-fqcn-reflection)
- [Templates](#templates)
- [Multi-Tenancy](#multi-tenancy)
- [Audit](#audit)
- [Design Decisions](#design-decisions)
- [Dependencies](#dependencies)
- [Building](#building)
- [License](#license)

---

## Features

- **Multi-Channel Support**: Email, SMS, WhatsApp, Push notifications
- **Multiple Providers per Channel**: SMTP, AWS SES, Twilio, Firebase FCM, etc.
- **Multi-Tenancy**: Tenant-specific configurations via `X-Tenant-Id` header
- **Caller Identity**: Optional `X-Service-Id` header — feeds idempotency dedup, audit, and an opt-in caller registry (DD-11)
- **Idempotency**: Optional `idempotencyKey` field with pluggable store (DD-10)
- **Rate Limiting**: Opt-in token-bucket throttle per `(tenant, caller, channel)` with `429 + Retry-After` (DD-12)
- **Retries + DLQ**: Opt-in synchronous retry with classified failures (TRANSIENT/PERMANENT/UNKNOWN) and exponential backoff with jitter; pluggable dead-letter store SPI (DD-13)
- **OpenAPI / Swagger**: Self-documenting via `/v3/api-docs` + `/swagger-ui` (springdoc 3.0.3); schema published as a CI build artifact for client codegen
- **Distributed mode**: Optional `notification-redis` module providing Redis-backed implementations of the idempotency, rate-limit, and DLQ SPIs for multi-pod deployments (DD-14)
- **Template Engine**: FreeMarker templates with tenant-specific overrides
- **Pluggable Providers**: Add custom providers via Spring Bean or FQCN
- **Dual Deployment**: Use as library (starter) or standalone Docker service
- **REST & Kafka**: Accept notifications via REST API or Kafka consumer
- **Audit Trail**: Optional persistence of notification history
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
│    ┌─────────┬───────────┼───────────┬─────────┐               │
│    ▼         ▼           ▼           ▼         ▼               │
│ ┌──────┐ ┌──────┐   ┌────────┐  ┌────────┐ ┌──────┐           │
│ │ SMTP │ │ SES  │   │ Twilio │  │  FCM   │ │ APNS │           │
│ └──────┘ └──────┘   └────────┘  └────────┘ └──────┘           │
└─────────────────────────────────────────────────────────────────┘
```

---

## Modules

| Module | Description |
|--------|-------------|
| `notification-api` | Core interfaces, DTOs, and exceptions |
| `notification-core` | Service implementation, provider registry, template engine |
| `notification-rest` | REST controllers and filters |
| `notification-kafka` | Kafka consumer for async notifications |
| `notification-audit` | Audit persistence (optional) |
| `notification-channels/*` | Channel-specific provider implementations |
| `notification-spring-boot-starter` | Auto-configuration for library mode |
| `notification-server` | Standalone application with Dockerfile |

---

## Quick Start

### As a Spring Boot Starter

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>notification-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Add providers you need -->
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>email-provider-smtp</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Configure in `application.yml`:

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

### Default Provider Selection

When a request doesn't specify a provider:

| Scenario | Behavior |
|----------|----------|
| Single provider configured | Auto-selected (no config needed) |
| Multiple providers, one with `default: true` | That provider is used |
| Multiple providers, none with `default: true` | Error: provider required in request |
| Multiple providers, 2+ with `default: true` | Startup failure |

### Template Configuration

```yaml
notification:
  template:
    base-path: classpath:/templates/
    cache-enabled: true
    cache-ttl-seconds: 3600
```

---

## REST API

### Live API documentation

The service exposes its OpenAPI 3.1 schema and an interactive Swagger UI
out of the box (springdoc 3.0.3, Phase 9):

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
    default:
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
data may carry PII). A future replay endpoint will re-submit by
request id.

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
| `/api/v1/admin/health` | GET | Provider health status |
| `/api/v1/admin/cache/templates/clear` | POST | Clear template cache |

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

### Option 1: Spring Bean

Create a Spring bean implementing the provider interface:

```java
@Component("myCustomEmailProvider")
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

**Resolution order:**
1. `templates/{tenantId}/{channel}/{notificationType}.ftl`
2. `templates/default/{channel}/{notificationType}.ftl`

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

Enable audit persistence:

```yaml
notification:
  audit:
    enabled: true
    store-request-payload: true
    store-response-payload: true
    retention-days: 90
    async: true
```

Audit records include:
- Request/response details
- Status transitions
- Provider message IDs
- Error information
- Timestamps

---

## Distributed deployment (Redis backends)

The default `notification-core` implementations of idempotency,
rate-limit, and DLQ are in-memory — correct for single-pod deployments
but wrong for multi-pod ones (each pod gets its own state). For
multi-pod / horizontally-scaled setups, pull the
`notification-redis` module which provides Redis-backed
implementations of all three SPIs. See
[DD-14](docs/design-decisions/14-distributed-redis-backends.md).

```xml
<dependency>
  <groupId>com.github.ifrugal</groupId>
  <artifactId>notification-redis</artifactId>
  <version>${notification-service.version}</version>
</dependency>
```

Each backend is independently toggleable so operators can migrate one
at a time:

```yaml
notification:
  redis:
    key-prefix: "notification-svc"     # avoids collisions on shared Redis
    idempotency:
      enabled: true                    # closes DD-10's foreseen-Redis SPI
    rate-limit:
      enabled: true                    # closes DD-12's foreseen-Redis SPI
    dead-letter:
      enabled: true                    # closes DD-13's foreseen-Redis SPI
      max-entries: 1000

# Connection details — Spring Data Redis honours these
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
```

The Redis-backed beans use `@ConditionalOnMissingBean` so a future
custom impl (Hazelcast, DynamoDB, etc.) wins automatically. They use
`@ConditionalOnClass(LettuceConnectionFactory.class)` so deployments
that don't pull `notification-redis` aren't affected.

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

## Design Decisions

Detailed design decisions are documented in:

| Document | Description |
|----------|-------------|
| [01-module-structure.md](docs/design-decisions/01-module-structure.md) | Module organization |
| [02-notification-request.md](docs/design-decisions/02-notification-request.md) | Request/response model |
| [03-multi-tenancy.md](docs/design-decisions/03-multi-tenancy.md) | Multi-tenant approach |
| [04-template-engine.md](docs/design-decisions/04-template-engine.md) | Template engine design |
| [05-provider-registration.md](docs/design-decisions/05-provider-registration.md) | Provider registration |
| [06-spi-discovery.md](docs/design-decisions/06-spi-discovery.md) | SPI discovery |
| [07-audit-persistence.md](docs/design-decisions/07-audit-persistence.md) | Audit storage |
| [08-packaging-modes.md](docs/design-decisions/08-packaging-modes.md) | Deployment modes |

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
