# Notification Service

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

Tenant can be passed via Kafka header `X-Tenant-Id`.

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
