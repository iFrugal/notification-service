# Decision 08: Starter vs Standalone Packaging

## Status: DECIDED

## Context
The notification service should support two deployment modes:
1. **Starter JAR**: Include in another Spring Boot application as a library
2. **Standalone**: Run as independent Docker container

## Decision

### Single Docker Image with External Configuration

Use a single Docker image that loads configuration from external `application.yml` to decide which channels and providers to enable.

### Mode 1: Spring Boot Starter (Library)

**Use Case:** Embed notification capability directly in your application.

```xml
<!-- User's pom.xml -->
<dependency>
    <groupId>com.lazydevs</groupId>
    <artifactId>notification-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Add only the channel providers you need -->
<dependency>
    <groupId>com.lazydevs</groupId>
    <artifactId>notification-channel-email-smtp</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Characteristics:**
- In-process calls (no network latency)
- User selectively includes channel modules
- User configures in their `application.yml`
- `NotificationService` bean auto-injected

```java
@Service
public class OrderService {

    @Autowired
    private NotificationService notificationService;

    public void processOrder(Order order) {
        notificationService.send(NotificationRequest.builder()
            .channel(Channel.EMAIL)
            .notificationType("ORDER_CONFIRMATION")
            .recipient(new EmailRecipient(order.getCustomerEmail()))
            .templateData(Map.of("orderId", order.getId()))
            .build());
    }
}
```

### Mode 2: Standalone Server (Docker)

**Use Case:** Centralized notification service for microservices.

```bash
docker run -p 8080:8080 \
  -v /path/to/config:/config \
  lazydevs/notification-service:latest \
  --spring.config.location=/config/application.yml
```

**Or with environment variables:**
```bash
docker run -p 8080:8080 \
  -e NOTIFICATION_TENANTS_DEFAULT_CHANNELS_EMAIL_ENABLED=true \
  -e NOTIFICATION_TENANTS_DEFAULT_CHANNELS_EMAIL_PROVIDERS_SMTP_HOST=smtp.gmail.com \
  lazydevs/notification-service:latest
```

**Characteristics:**
- All built-in channels included in image
- External `application.yml` controls what's enabled
- REST API and Kafka listener enabled by default
- Clients call via HTTP or Kafka

---

## Configuration API

### Endpoint: List All Tenants and Their Configuration

```
GET /api/v1/admin/configuration
```

**Response:**
```json
{
  "defaultTenant": "default",
  "tenants": {
    "default": {
      "channels": {
        "email": {
          "enabled": true,
          "defaultProvider": "smtp",
          "config": {
            "from-address": "noreply@example.com"
          },
          "providers": {
            "smtp": {
              "status": "ACTIVE",
              "type": "BUILT_IN",
              "config": {
                "host": "smtp.gmail.com",
                "port": 587
              }
            },
            "ses": {
              "status": "ACTIVE",
              "type": "BUILT_IN",
              "config": {
                "region": "us-east-1"
              }
            }
          }
        },
        "sms": {
          "enabled": true,
          "defaultProvider": "twilio",
          "providers": {
            "twilio": {
              "status": "ACTIVE",
              "type": "BUILT_IN",
              "config": {
                "from": "+1234567890"
              }
            }
          }
        },
        "whatsapp": {
          "enabled": false,
          "providers": {}
        },
        "push": {
          "enabled": false,
          "providers": {}
        }
      }
    },
    "tenant-a": {
      "channels": {
        "email": {
          "enabled": true,
          "defaultProvider": "ses",
          "config": {
            "from-address": "tenant-a@example.com"
          },
          "providers": {
            "ses": {
              "status": "ACTIVE",
              "type": "BUILT_IN",
              "config": {
                "region": "eu-west-1"
              }
            }
          }
        }
      }
    }
  }
}
```

### Endpoint: Get Specific Tenant Configuration

```
GET /api/v1/admin/configuration/tenants/{tenantId}
```

### Endpoint: Get Channel Configuration for Tenant

```
GET /api/v1/admin/configuration/tenants/{tenantId}/channels/{channel}
```

### Endpoint: Health Check with Provider Status

```
GET /api/v1/admin/health
```

**Response:**
```json
{
  "status": "UP",
  "providers": {
    "default:email:smtp": "HEALTHY",
    "default:email:ses": "HEALTHY",
    "default:sms:twilio": "HEALTHY",
    "tenant-a:email:ses": "HEALTHY"
  },
  "kafka": {
    "status": "CONNECTED",
    "topic": "notifications"
  }
}
```

---

## Module Responsibilities

| Module | Starter Mode | Standalone Mode |
|--------|--------------|-----------------|
| `notification-api` | ✅ Always | ✅ Always |
| `notification-core` | ✅ Always | ✅ Always |
| `notification-channels/*` | 🔸 User selects | ✅ All included |
| `notification-kafka` | 🔸 Optional | ✅ Included |
| `notification-rest` | 🔸 Optional | ✅ Included |
| `notification-audit` | 🔸 Optional | 🔸 Configurable |
| `notification-spring-boot-starter` | ✅ Entry point | ❌ Not used |
| `notification-server` | ❌ Not used | ✅ Entry point |

---

## Configuration Examples

### Starter Mode - User's application.yml

```yaml
notification:
  # REST endpoints disabled in library mode (use NotificationService bean directly)
  rest:
    enabled: false

  # Kafka optional
  kafka:
    enabled: false

  # Audit optional
  audit:
    enabled: false

  tenants:
    default:
      channels:
        email:
          enabled: true
          default-provider: smtp
          providers:
            smtp:
              host: smtp.gmail.com
              port: 587
```

### Standalone Mode - application.yml

```yaml
server:
  port: 8080

notification:
  # REST always enabled for standalone
  rest:
    enabled: true
    base-path: /api/v1

  # Kafka enabled for async processing
  kafka:
    enabled: true
    topic: notifications
    group-id: notification-service

  # Audit enabled for tracking
  audit:
    enabled: true
    persistence:
      type: mongodb

  tenants:
    default:
      channels:
        email:
          enabled: true
          default-provider: smtp
          config:
            from-address: ${EMAIL_FROM:noreply@example.com}
          providers:
            smtp:
              host: ${SMTP_HOST:smtp.gmail.com}
              port: ${SMTP_PORT:587}
              username: ${SMTP_USER}
              password: ${SMTP_PASS}
            ses:
              region: ${AWS_REGION:us-east-1}

        sms:
          enabled: ${SMS_ENABLED:false}
          default-provider: twilio
          providers:
            twilio:
              account-sid: ${TWILIO_SID}
              auth-token: ${TWILIO_TOKEN}
              from: ${TWILIO_FROM}

        whatsapp:
          enabled: ${WHATSAPP_ENABLED:false}
          default-provider: twilio
          providers:
            twilio:
              account-sid: ${TWILIO_SID}
              auth-token: ${TWILIO_TOKEN}
              from: ${TWILIO_WHATSAPP_FROM}

        push:
          enabled: ${PUSH_ENABLED:false}
          default-provider: fcm
          providers:
            fcm:
              credentials-path: ${FCM_CREDENTIALS_PATH:/config/fcm-credentials.json}
```

---

## Sensitive Config Masking

The configuration API will mask sensitive values:

```java
public class ConfigurationMasker {

    private static final Set<String> SENSITIVE_KEYS = Set.of(
        "password", "secret", "token", "api-key", "auth-token",
        "credentials", "private-key", "account-sid"
    );

    public Map<String, Object> maskSensitive(Map<String, Object> config) {
        return config.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> isSensitive(e.getKey()) ? "***MASKED***" : e.getValue()
            ));
    }

    private boolean isSensitive(String key) {
        String lower = key.toLowerCase();
        return SENSITIVE_KEYS.stream().anyMatch(lower::contains);
    }
}
```

**API Response with masking:**
```json
{
  "providers": {
    "smtp": {
      "host": "smtp.gmail.com",
      "port": 587,
      "username": "user@gmail.com",
      "password": "***MASKED***"
    }
  }
}
```

---

## Summary

| Aspect | Decision |
|--------|----------|
| Docker images | Single image, config-driven |
| Channel enablement | Via `application.yml` |
| Provider enablement | Via `application.yml` |
| Configuration API | Yes, with sensitive masking |
| Starter REST endpoints | Disabled by default |
| Starter Kafka | Disabled by default |
| Standalone REST | Always enabled |
| Standalone Kafka | Enabled by default |

## Reasoning

### Why single Docker image
1. **Simpler maintenance**: One image to build, test, publish
2. **Flexible activation**: Enable/disable via config, not image variants
3. **Consistent**: Same code in all deployments

### Why config-driven enablement
1. **No rebuild needed**: Change config, restart
2. **Environment-specific**: Dev uses SMTP, prod uses SES
3. **Tenant isolation**: Different tenants, different providers

### Why configuration API
1. **Observability**: See what's configured without checking YAML
2. **Debugging**: Verify provider status
3. **Operations**: Health checks for monitoring

### Why mask sensitive values
1. **Security**: Don't expose secrets in API responses
2. **Compliance**: Audit-safe configuration view
3. **Debugging**: Still shows structure, just not values

## Related Decisions
- [05-provider-registration.md](./05-provider-registration.md) - Provider configuration
- [03-multi-tenancy.md](./03-multi-tenancy.md) - Tenant configuration
