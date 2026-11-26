# Feature Matrix — what to add, what to configure

> **Read this first** if you're integrating `notification-service` and asking
> *"what do I actually need?"*
>
> Every cross-cutting feature is **opt-in by default**. The base starter
> sends notifications and that's it. Pull the features you want, set their
> flag to `true`, you're done.
>
> One exception since 1.4.0: push template sections (DD-37) are on by default; see [Push to a user](#push-to-a-user-device-registry-dd-37-since-140).

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
    <version>1.1.0</version>
</dependency>
```

| What you get | Status | Notes |
|--------------|--------|-------|
| Core service (`NotificationService.send(...)`) | 🟢 | Always present |
| Template engine (FreeMarker) | 🟢 | Tenant-aware lookup; includes and imports resolve against `notification.template.base-path` (DD-26); configurable via `notification.template.*` |
| Multi-tenant routing (`X-Tenant-Id`) | 🟢 | DD-03 |
| Provider registry + lifecycle | 🟢 | DD-05 / DD-06 |
| In-memory idempotency store (Caffeine) | 🟢 | DD-10; `notification.idempotency.enabled` defaults to `true` |
| REST transport (`/api/v1/notifications`, `/api/v1/admin/*`) | 🔵 📦 | Off by default since 1.1.0. Add `notification-rest` (optional in the starter) and set `notification.rest.enabled: true` |
| Version alignment (`notification-service-bom`) | 🔵 📦 | Since 1.2.0. Import the BOM once and leave the versions off the starter, the providers and the stores, see below |

**Keep every module at one version with the BOM (since 1.2.0):**

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.github.ifrugal</groupId>
            <artifactId>notification-service-bom</artifactId>
            <version>1.2.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

The BOM manages only this project's artifacts, not Spring Boot, Jackson or any provider SDK.
With it, the `<version>` elements in the snippets below can be left out.

**Minimum `application.yml`:**

```yaml
notification:
  default-tenant: default
  rest:
    enabled: true                  # only if you added notification-rest and want the HTTP API
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
Each provider module registers its provider through its own auto-configuration, as a prototype bean named `<name><Channel>Provider` (for example `smtpEmailProvider`).
A provider that is configured but whose module is missing fails startup with a message naming the artifact to add.

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
| Azure Communication Services | `email-provider-acs` | Azure-native email (ACS Email) | `sender` plus `connection-string`, or `endpoint` + `credential` (`default` for managed identity via azure-identity, or a `TokenCredential` bean name); optional `reply-to`, `send-mode` (`wait`/`submit`), `wait-timeout`, `sdk-retries`, `user-engagement-tracking-disabled`, `timeout-failure-type` (`transient` default, or `ambiguous` since 1.2.1, DD-31). See the [module README](../notification-channels/notification-channel-email/email-provider-acs/README.md) |

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>email-provider-smtp</artifactId>
    <version>1.1.0</version>
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
| AWS SNS | Planned (no artifact) | AWS-native SMS, cheaper for transactional | Not defined yet |

### 2c. WhatsApp channel

The Meta WhatsApp Cloud API ships as `whatsapp-provider-meta` since 1.3.0, on top of the vendor-neutral `whatsapp-core` (DD-35).
Messages are templates approved at the provider; `whatsapp-core` maps the request's `templateId` (else `notificationType`) onto the approved template and fills its parameters from `templateData`, instead of rendering a FreeMarker file.

| Provider | Artifact | When to use | Required properties |
|----------|----------|-------------|---------------------|
| Meta | `whatsapp-provider-meta` (brings `whatsapp-core`) | Direct Meta WhatsApp Cloud API (Graph API) | `phone-number-id` and `access-token`, unless a `MetaCredentialsProvider` bean supplies them per tenant; optional `api-version` (default `v25.0`, pinned), `base-url`, `timeout`, `pair-rate-limit-retry-after`. See the [module README](../notification-channels/notification-channel-whatsapp/whatsapp-provider-meta/README.md) |
| Template registry | `whatsapp-core` | Comes with every WhatsApp provider | `notification.whatsapp.templates.<tenant>.<templateId>`: `name`, `language`, `header`, `body`, `buttons`. See the [module README](../notification-channels/notification-channel-whatsapp/whatsapp-core/README.md) |
| Twilio | Planned (no artifact) | Twilio's WhatsApp Business API | Not defined yet |

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>whatsapp-provider-meta</artifactId>
    <version>1.3.0</version>
</dependency>
```

```yaml
notification:
  tenants:
    default:
      channels:
        whatsapp:
          enabled: true
          providers:
            meta:
              properties:
                phone-number-id: ${WHATSAPP_META_PHONE_NUMBER_ID}   # <phone-number-id>
                access-token: ${WHATSAPP_META_ACCESS_TOKEN}
  whatsapp:
    templates:
      default:
        order-shipped:
          name: order_shipped
          language: en
          body: [customerName, orderNumber]
```

Failures carry a documented `errorCode` (`THROTTLED`, `PAIR_RATE_LIMITED`, `TEMPLATE_REJECTED`, `CREDENTIAL_REJECTED`, ...) and a `FailureType`; the provider never retries on its own.
Message statuses (`sent`, `delivered`, `read`, `failed`) arrive through the Meta webhook, see 3d.
A Retry-After hint reaches the caller as `NotificationResponse.retryAfter`; to release the idempotency key after `CREDENTIAL_REJECTED` too, declare an `IdempotencyReleasePolicy` bean (see the module README).
The standalone server bundles both modules.

### 2d. Push channel

Firebase Cloud Messaging ships as `push-provider-fcm` since 1.2.0.
Apple APNs direct is planned; the `PushProvider` SPI in `notification-api` is the extension point for any other push service.

| Provider | Artifact | When to use | Required properties |
|----------|----------|-------------|---------------------|
| Firebase FCM | `push-provider-fcm` | Cross-platform (Android + iOS + web) push over the FCM HTTP v1 API | `credentials`: a service-account JSON file path or the inline JSON (`adc` and `external-account:<path>` need `push-provider-fcm-google-auth`); `project-id` when the key does not name one; optional `validate-only`, `dry-run`, `timeout`, `concurrency`, `multi-token-policy` (`all`/`any`), `android.*`/`apns.*`/`webpush.*` defaults. See the [module README](../notification-channels/notification-channel-push/push-provider-fcm/README.md) |
| FCM credentials adapter | `push-provider-fcm-google-auth` | Add to `push-provider-fcm` for `credentials: adc` (Application Default Credentials), `credentials: external-account:<path>` (workload identity federation) or, since 1.4.0, `credentials: external-account-supplier:<name>` (federation with an `FcmSubjectTokenSupplier` bean, no file) | None of its own; every token call goes through the tenant's `FcmHttpTransport`. Brings the Google auth library (about 4.9 MB, no Netty). See the [module README](../notification-channels/notification-channel-push/push-provider-fcm-google-auth/README.md) |
| Apple APNs | Planned (no artifact) | iOS-only push direct to Apple | Not defined yet |

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>push-provider-fcm</artifactId>
    <version>1.2.0</version>
</dependency>

<!-- Only for credentials: adc or external-account:<path> -->
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>push-provider-fcm-google-auth</artifactId>
    <version>1.2.0</version>
</dependency>
```

`push-provider-fcm` adds no dependency beyond `notification-api`: no HTTP library and no Google library.
The standalone server bundles both modules.
A device token that FCM reports as unregistered is published as a `BOUNCED` delivery event with reason `INVALID_TARGET` and a hash of the token, so a `DeliveryEventListener` can delete it.

#### Push to a user: device registry (DD-37, since 1.4.0)

No new artifact: the registry is in `notification-core` (in memory) and `notification-store-jdbc` (`<prefix>push_device`).

```yaml
notification:
  push:
    devices:
      enabled: true                    # in-memory, or JDBC with notification.store.type=jdbc
      max-devices-per-user: 10
      stale-after: 60d
      purge-after: 270d                # JDBC: expired devices are purged with the other stores
      cleanup-on-invalid-target: true  # remove a device when FCM reports its token invalid
    user-targets:
      multi-token-policy: any          # any | all, for sends addressed to a user
    template-sections:
      enabled: true                    # [TITLE] / [BODY] / [DATA key="..."] in push templates
```

| What | How |
|------|-----|
| Register a device | `PushDeviceRegistry.register(PushDeviceRegistration)`; the scope is `TenantContext` (none = pool) |
| Send to a user | `PushRecipient.forUser(userId)` or `forUser(userId, appId)` |
| No active device | `FAILED` / `NO_DEVICES` / `PERMANENT`, no provider call, no dead letter |
| Dead token | Removed by `PushDeviceCleanupListener` from the FCM `INVALID_TARGET` event |
| Title and data from a template | `[TITLE]`, `[BODY]` and `[DATA key="..."]` sections; the caller's `data` wins over the template's |

The Redis store family has no registry: declare your own `PushDeviceRegistry` bean or startup fails.

Device registration and user targeting are opt-in, but push template sections are on by default (`notification.push.template-sections.enabled`, default `true`).
An application with its own `PUSH` `ChannelContentRenderer` must set it to `false`.
Sections are supported only in the top-level template, not in an `<#include>`d or `<#import>`ed file.

### 2e. Planned providers

FCM (HTTP v1 API) shipped in 1.2.0 and the Meta WhatsApp Cloud API with a signed webhook in 1.3.0.
AWS SNS SMS, Twilio or MSG91 WhatsApp and Apple APNs are planned but not scheduled yet.
The `SmsProvider`, `WhatsAppProvider` and `PushProvider` SPIs are the extension points in the meantime.

### 2f. Unpublished artifacts

The artifactIds `sms-provider-sns`, `whatsapp-provider-twilio`, `whatsapp-provider-meta`, `push-provider-fcm`, `push-provider-apns` and `notification-audit` exist on Maven Central for versions 1.0.0 to 1.0.2, but only as empty jars with no classes.
They are not published from 1.1.0 on.
If your build declares any of them, remove the dependency; you lose nothing, because they never contained code.
The aggregator poms `notification-channel-whatsapp` and `notification-channel-push` are dropped for the same reason.
The exceptions are `push-provider-fcm` (and its aggregator `notification-channel-push`), published again from 1.2.0 with the real FCM provider, and `whatsapp-provider-meta` (and its aggregator `notification-channel-whatsapp`), published again from 1.3.0 with the real Meta provider; versions 1.0.0 to 1.0.2 of them are still empty.

---

## 3. Cross-cutting features

Each row = one independently-toggleable feature. Default-off unless noted.
All have an in-memory default; the stateful ones can move to Redis or PostgreSQL with `notification.store.type` (see [section 4](#4-distributed-mode-multi-pod)).

| Feature | DD | Default | Enable flag | In-memory default | Redis (`store.type: redis`) | JDBC (`store.type: jdbc`) |
|---------|----|---------|-------------|-------------------|-----------------------------|---------------------------|
| **Idempotency** | DD-10 | 🟢 On | `notification.idempotency.enabled: true` | Caffeine (in `notification-core`, always present) | `RedisIdempotencyStore` | `JdbcIdempotencyStore` |
| **Caller identity** (`X-Service-Id`) | DD-11 | 🟢 On (registry off) | `notification.caller-registry.enabled: true` to enforce | n/a | n/a | n/a |
| **Rate limiting** | DD-12 | 🔵 Off | `notification.rate-limit.enabled: true` | Bucket4j (in `notification-core`) | `RedisRateLimiter` | None: memory or Redis only |
| **Retries** | DD-13 | 🔵 Off | `notification.retry.enabled: true` | Built-in | n/a | n/a |
| **Dead-letter queue** | DD-13 | 🔵 Off | `notification.dead-letter.enabled: true` | Caffeine LRU (in `notification-core`) | `RedisDeadLetterStore` | `JdbcDeadLetterStore` (replica-safe replay) |
| **Per-channel retry + rate-limit overrides** | DD-23 | n/a | composes on top of retry / rate-limit when those are enabled | n/a | n/a | n/a |
| **Webhook ingestion** (Twilio, SES, Meta WhatsApp) | DD-16, DD-36 | 🔵 Off | `notification.webhooks.enabled: true` + per-provider; the REST route needs REST, the `WebhookDispatcher` works without it | n/a | n/a | n/a |
| **Delivery event store** | DD-17 | 🔵 Off | `notification.delivery-events.enabled: true` | Caffeine LRU | `RedisDeliveryEventStore` | `JdbcDeliveryEventStore` |
| **Audit persistence** | DD-07, DD-33 | 🔵 Off | `notification.audit.enabled: true` | `InMemoryNotificationAuditStore` (bounded) | None: supply a `NotificationAuditStore`, or `notification.redis.audit.enabled: false` for memory | `JdbcNotificationAuditStore` (table `<prefix>audit`) |
| **Legacy template helpers** (`eval`, `file`, `js`, `serde_*`, unrestricted `?new`) | DD-26 | 🟢 On (deprecated; off from 2.0) | `notification.template.legacy-helpers: false` to remove | n/a | n/a | n/a |
| **Validate before dispatch** (render first, 422 on failure) | DD-27 | 🔵 Off (on from 2.0) | `notification.template.validate-before-dispatch: true` | n/a | n/a | n/a |
| **Default-tenant provider fallback** | DD-28 | 🔵 Off | `notification.tenant-fallback.enabled: true` | n/a | n/a | n/a |
| **Lazy provider initialisation** | DD-29 | 🔵 Off | `notification.providers.lazy-init: true` | n/a | n/a | n/a |
| **Rate-limit bandwidths, shared scope, refunds** | DD-30 | n/a | composes on top of rate-limit when it is enabled | Bucket4j (in `notification-core`) | `RedisRateLimiter` | None: memory or Redis only |
| **Failure type, provider metadata, retry hint on responses** | DD-31 | 🟢 On | always; `failureType`, `providerMetadata` and `retryAfter` appear in the response when set | n/a | n/a | n/a |
| **Idempotency key-reuse check** | DD-32 | 🔵 Off | `notification.idempotency.reject-key-reuse: true` | fingerprint in the Caffeine record | fingerprint in the Redis record | `fingerprint` column (optional; required for this check) |
| **Idempotency failure policy + release policy SPI** | DD-32 | 🟢 `RELEASE_ALL` | `notification.idempotency.failure-policy: RELEASE_TRANSIENT` (or `KEEP`); `IdempotencyReleasePolicy` bean to replace | Caffeine | `RedisIdempotencyStore` | `JdbcIdempotencyStore` |
| **Idempotency in-progress timeout** | DD-32 | 🔵 Off | `notification.idempotency.in-progress-timeout: PT10M` | compare-and-set | Lua compare-and-set | conditional `UPDATE` |
| **Scrubbed error message in stored failures** | DD-32 | 🔵 Off | `notification.idempotency.store-scrubbed-error-message: true` | Caffeine | `RedisIdempotencyStore` | `JdbcIdempotencyStore` |
| **Status view** (`NotificationService.find`) | DD-33 | 🔵 Off | `notification.audit.enabled: true` | `InMemoryNotificationAuditStore` (bounded) | None: supply a store, or `notification.redis.audit.enabled: false` for memory | `JdbcNotificationAuditStore` |
| **Recipient guard** | DD-34 | 🔵 Off | `notification.recipient-guard.enabled: true` + `channels.<channel>.allowed` | n/a | n/a | n/a |

### 3a. Idempotency (DD-10)

**Already on by default with a Caffeine in-memory store.** Customers want
multi-pod?
Switch the store family to Redis (or `jdbc`, see section 4):

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>notification-redis</artifactId>
    <version>1.1.0</version>
</dependency>
```

```yaml
notification:
  idempotency:
    enabled: true                  # default true; reaffirm explicitly
    ttl: 24h
    max-entries: 100000

  store:
    type: redis                    # ⚠️ requires Redis reachable

  redis:
    key-prefix: notification-svc

spring:
  data:
    redis:
      host: redis.internal
      port: 6379
```

Send-side: include `idempotencyKey` on `NotificationRequest`. Replay of a
completed key returns `200` with header `X-Idempotent-Replay: true`.
Concurrent duplicate returns `409`.

Since 1.2.1 (DD-32):

```yaml
notification:
  idempotency:
    failure-policy: RELEASE_TRANSIENT     # RELEASE_ALL (default) | KEEP | RELEASE_TRANSIENT
    reject-key-reuse: true                # default false
    in-progress-timeout: PT10M            # default unset (409 until the TTL)
    store-scrubbed-error-message: true    # default false
```

- `RELEASE_TRANSIENT` releases the key only after a `TRANSIENT` failure; `PERMANENT`, `AMBIGUOUS` and `UNKNOWN` failures are replayed (`X-Idempotent-Replay: true`, status `FAILED`, with `failureType`).
- The release decision is the `IdempotencyReleasePolicy` bean (`com.lazydevs.notification.api.idempotency`); declare your own to replace it.
  A key settled by the in-progress timeout is never released.
- `reject-key-reuse` answers a key reused for a different request (different channel, recipient, template, template data, explicit provider or attachments) with `409 {"notificationId": "...", "status": "KEY_REUSED"}`.
  Correlation id and metadata do not count.
  On JDBC it needs the `fingerprint` column; without it startup fails with the `ALTER TABLE` statement.
- `in-progress-timeout` settles a key stuck `IN_PROGRESS` as `FAILED` / `IN_PROGRESS_TIMEOUT` / `AMBIGUOUS` and replays it; it is never released.
- `store-scrubbed-error-message` keeps the error message of a stored failure, with addresses and numbers replaced by placeholders (`MessageScrubber`); by default the stored copy has none, as in 1.2.0.
- `retry-after-failure` still works as a deprecated alias (`true` = `RELEASE_ALL`, `false` = `KEEP`); `failure-policy` wins when both are set.

### 3a-1. Recipient guard (DD-34)

```yaml
notification:
  recipient-guard:
    enabled: true
    channels:
      email:
        allowed: ["@example.com", "qa.lead@example.org"]
        redirect-to: qa-inbox@example.com
        subject-prefix: "[redirected: {originals}] "   # default; "" disables
      sms:
        allowed: ["+15550100"]                         # no redirect-to: drop
      whatsapp:
        allowed: ["+15550101"]                         # the WhatsApp phone number
        redirect-to: "+15550101"
```

Not-allowed recipients are redirected one-for-one (or dropped); nothing left fails the send with `RECIPIENT_BLOCKED` (`PERMANENT`) before the provider.
Push recipients are not guarded.
Declare a `RecipientGuard` bean to replace the rules.

### 3a-2. Default-tenant provider fallback (DD-28)

```yaml
notification:
  default-tenant: default
  tenant-fallback:
    enabled: true                  # default false
  tenants:
    default:                       # serves every tenant without its own entry
      channels:
        email:
          providers:
            smtp:
              properties:
                host: smtp.example.com
```

Only provider resolution falls back.
Idempotency, rate limits, templates, audit and the dead-letter store keep the request's own tenant id.
A missing default tenant still fails with `TENANT_NOT_FOUND`.

### 3a-3. Lazy provider initialisation (DD-29)

```yaml
notification:
  providers:
    lazy-init: true                # default false
```

The application boots even when an enabled provider has no settings yet (also in a native image).
The first send that needs the provider builds it; concurrent first sends share one build.
A failed build fails the send with `PROVIDER_NOT_CONFIGURED` as a `TRANSIENT` failure (retry and DLQ apply) and is tried again on the next send.
`GET /admin/health` lists `NOT_INITIALIZED` (status stays `UP`) and `INIT_FAILED` (status `DEGRADED`) providers.

### 3a-4. Template rendering (DD-26)

```yaml
notification:
  template:
    base-path: classpath:/templates/
    legacy-helpers: false          # default true in 1.x, deprecated; false from 2.0
```

`<#include>` and `<#import>` resolve against `base-path` (`classpath:` or `file:`): relative names next to the including template, names starting with `/` from `base-path`.
Every path segment must pass the template id rule and `..` cannot leave `base-path`.
`legacy-helpers: false` removes `eval`, `file`, `js`, `serde_deserializeToMap` and `serde_serialize` and limits `?new` with FreeMarker's `SAFER_RESOLVER`; `uuid`, `trim` and the notification helpers stay.
With `true` the engine logs one WARN at startup.
FreeMarker no longer logs render errors; they are thrown once as `TemplateRenderException` with a reason and, for model errors, the variable path.

### 3a-5. Validate before dispatch (DD-27)

```yaml
notification:
  template:
    validate-before-dispatch: true   # default false; planned default in 2.0
```

The template is rendered before the idempotency check, the rate limit and the audit record.
A request whose template is missing, invalid, does not fit its data, or renders an email with a blank subject or an empty body is refused with `TemplateRenderException`: HTTP 422 over REST (`error`, `reason`, `templateId`, `path`, `message`), one WARN and an offset commit on Kafka.
Nothing is stored, no rate-limit token is taken and nothing goes to the dead-letter store.
The rendered content is reused for the send, so the template is rendered once.
A channel with its own renderer (WhatsApp, DD-35) is rendered by it at this point instead; a WhatsApp value that breaks a parameter rule is refused the same way, with reason `PARAMETER_INVALID` (`TEMPLATE_PARAMETER_INVALID`) and the variable as `path`.

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

Since 1.2.1 (DD-30):

```yaml
notification:
  rate-limit:
    enabled: true
    refund-unsent: false           # DD-30: refund a failed send whose idempotency key was released
    by-channel:
      sms:
        scope: channel             # one bucket for all tenants and callers
        bandwidths:                # every band must allow
          - capacity: 2
            refill-tokens: 2
            refill-period: 1m
          - capacity: 5
            refill-tokens: 5
            refill-period: 1h
```

`bandwidths` and the single-band fields are mutually exclusive on one rule.
`scope: channel` is applied after rule precedence, so a tenant override still applies to its tenant.
`refund-unsent` gives the token back to the bucket the send consumed when the idempotency release policy released the key of a failed send, unless the failure is `AMBIGUOUS` or `UNKNOWN`.

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
    replay-lease: PT5M             # how long a replay holds its claim on an entry
```

Operator endpoints (DD-15 + DD-19):
- `GET /api/v1/admin/dead-letter` — recent failures (PII-safe)
- `POST /api/v1/admin/dead-letter/{requestId}/replay` — single replay
- `POST /api/v1/admin/dead-letter/replay-batch?tenantId=acme&dryRun=true` — bulk

Replay claims each entry for `replay-lease` before sending, removes it on success and releases it on failure.
An entry another replay holds is skipped: `409` with `status: CLAIMED` for a single replay, a `CLAIMED` row counted under `claimed` in a batch.
Only the JDBC store enforces claims across replicas; the in-memory and Redis stores do not lease.

For distributed (multi-pod) DLQ:

```yaml
notification:
  store:
    type: jdbc                     # or redis
  redis:
    dead-letter:
      max-entries: 10000           # Redis list cap, when Redis backs the DLQ
```

### 3d. Webhooks - provider delivery callbacks (DD-16 + DD-17 + DD-36)

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
    meta:                          # needs whatsapp-provider-meta (since 1.3.0)
      enabled: true
      app-secret: ${META_APP_SECRET}        # your-app-secret
      verify-token: ${META_VERIFY_TOKEN}    # your own value, entered in the Meta app

  delivery-events:                 # store for querying via admin endpoint
    enabled: true
    max-entries: 5000
```

URLs to register with providers:
- Twilio status callback URL: `https://your.host/api/v1/webhooks/twilio/status`
- SES → SNS topic subscription URL: `https://your.host/api/v1/webhooks/ses/sns`
- Meta WhatsApp callback URL (GET handshake and POST statuses): `https://your.host/api/v1/webhooks/meta`

Every path is `<rest base path><webhooks base path>/{handlerId}/**`, routed to the `WebhookHandler` bean with that id (DD-36).
Without the REST module, call `WebhookDispatcher.dispatch(handlerId, request)` from your own endpoint.
WhatsApp statuses map to `SENT`, `DELIVERED`, `READ`, `BOUNCED` and `FAILED_AT_PROVIDER`; `READ` can arrive before `DELIVERED`, so never move a projected status backwards.

Query operator endpoints:
- `GET /api/v1/admin/delivery-events`
- `GET /api/v1/admin/delivery-events?requestId=req-abc` — joins via audit (DD-18)

**FCM is not supported** — Firebase doesn't ship per-message webhooks today.
The FCM provider reports invalid device tokens from the send response instead, as `BOUNCED` events to the same listeners and store (DD-24, DD-25).

---

## 4. Distributed mode (multi-pod)

For deployments running 2+ pods, the in-memory stores (idempotency,
rate-limit buckets, DLQ, delivery events) live in each pod separately.
Add the Redis or the JDBC backend so all pods share one source of truth.

### 4a. How the starter wires stores

The starter does not component-scan.
Every module (core, REST, Kafka, Redis, JDBC and each provider) registers its beans through its own `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, so adding a jar is the wiring.
The Redis and JDBC auto-configurations run before the core in-memory defaults, so a selected Redis or JDBC store wins and the default backs off; a bean of the same SPI that you declare yourself wins over both.
Beans of your own under `com.lazydevs.notification.*` are not picked up by the starter; register them yourself.

`notification.store.type` (`memory` by default, `redis` or `jdbc`) selects the family; per feature:

1. The feature flag (`notification.<feature>.enabled`) is the master switch.
   A disabled feature gets no store.
2. An explicit `notification.redis.<feature>.enabled` overrides the family for that feature: `true` is Redis, `false` is memory.
   It does not switch the feature on.
3. Otherwise `notification.store.type` decides.

An enabled feature whose selected family's module is missing fails startup, naming the artifact to add.

### 4b. Redis

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>notification-redis</artifactId>
    <version>1.1.0</version>
</dependency>
```

```yaml
notification:
  store:
    type: redis
```

| Redis-backed | Selected by | What it replaces |
|--------------|-------------|-------------------|
| Idempotency | `store.type: redis`, or `notification.redis.idempotency.enabled: true` | `CaffeineIdempotencyStore` |
| Rate limiting | `store.type: redis`, or `notification.redis.rate-limit.enabled: true` | `Bucket4jRateLimiter` (in-process) |
| Dead-letter queue | `store.type: redis`, or `notification.redis.dead-letter.enabled: true` | `InMemoryDeadLetterStore` |
| Delivery event store | `store.type: redis`, or `notification.redis.delivery-events.enabled: true` | `InMemoryDeliveryEventStore` |

### 4c. JDBC (PostgreSQL)

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
    jdbc:
      schema: notify                 # optional; default is the search path
      table-prefix: notification_    # default
      datasource-bean-name: appDs    # optional; default is the single or @Primary DataSource
      purge:
        enabled: true                # optional scheduled purge of expired rows
```

| JDBC-backed | Implementation | What it replaces |
|-------------|----------------|-------------------|
| Idempotency | `JdbcIdempotencyStore` | `CaffeineIdempotencyStore` |
| Dead-letter queue | `JdbcDeadLetterStore` (claims leased with `FOR UPDATE SKIP LOCKED`) | `InMemoryDeadLetterStore` |
| Delivery event store | `JdbcDeliveryEventStore` | `InMemoryDeliveryEventStore` |
| Push device registry (1.4.0, `notification.push.devices.enabled=true`) | `JdbcPushDeviceRegistry` | `InMemoryPushDeviceRegistry` |
| Rate limiting | None: memory or Redis only | n/a |

The host runs the migrations from the reference DDL in the jar (`db/postgresql/notification-store.sql`).
See the [module README](../notification-store-jdbc/README.md) for the schema, grants, row-level security and purge.

### 4d. Redis connection

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

### 4e. Read raw entries

Operators can `redis-cli LRANGE notification-svc:dlq 0 -1` directly —
all stored values are human-readable JSON for forensics. Same for
delivery events under `<prefix>:delivery-events`.

---

## 5. Observability (DD-21 + DD-22)

### 5a. Health indicators (DD-21)

Registered whenever the corresponding store or limiter bean exists, whether it is a default, a Redis or JDBC store, or your own bean.
Each indicator participates in the rolled-up `/actuator/health`:

| Indicator | Surfaces at | Present when a bean of |
|-----------|-------------|------------------------|
| DLQ | `/actuator/health/dlq` | `DeadLetterStore` exists (e.g. `notification.dead-letter.enabled: true`) |
| Idempotency | `/actuator/health/idempotency` | `IdempotencyStore` exists (e.g. `notification.idempotency.enabled: true`) |
| Rate limiter | `/actuator/health/rateLimit` | `RateLimiter` exists (e.g. `notification.rate-limit.enabled: true`) |
| Delivery events | `/actuator/health/deliveryEvents` | `DeliveryEventStore` exists (e.g. `notification.delivery-events.enabled: true`) |

DLQ flips to `Status.OUT_OF_SERVICE` at near-fullness — configurable:

```yaml
notification:
  health:
    dlq-near-full-percent: 80      # default
```

### 5b. Micrometer metrics (DD-22)

Registered when a `MeterRegistry` bean exists, which Spring Boot actuator's metrics auto-configuration provides (it does on `notification-server`).
Micrometer on the classpath without a registry bean is not enough.
No flag needed; Boot's `management.metrics.enable.notification=false` disables.

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
| `notification.template.validation.failed.total` | counter | channel, reason |

Standalone deployments ship with a Prometheus registry — scrape
`/actuator/prometheus`.

---

## 6. Transports

| Transport | JAR | Default | Enable | Notes |
|-----------|-----|---------|--------|-------|
| **REST** | `notification-rest` (📦 add explicitly; optional in the starter) | 🔵 Off since 1.1.0 | `notification.rest.enabled: true` | Endpoints under `${notification.rest.base-path:/api/v1}/notifications` and `/admin/...`; the tenant and caller filters run only under the base path, and webhooks need REST |
| **Kafka** | `notification-kafka` (📦 add explicitly) | 🔵 Off | `notification.kafka.enabled: true` | Honours `X-Tenant-Id` + `X-Service-Id` headers |
| **Programmatic** | `notification-api` (always) | 🟢 On | n/a | Inject `NotificationService` directly |

### Kafka consumer

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>notification-kafka</artifactId>
    <version>1.1.0</version>
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

**Shipped stores since 1.2.1 (DD-33).** With `enabled: true` the records go to the audit store of the store family: a bounded in-memory store (`notification.audit.max-entries`, default 10000) for `memory`, the `<prefix>audit` table for `jdbc`.
The Redis family has no audit store: supply a `NotificationAuditStore` bean, or set `notification.redis.audit.enabled: false` to keep audit in memory.
With `enabled: false` (the default) `NoOpAuditService` logs only.
A `NotificationAuditService` bean of your own replaces all of this (DD-07). Admin browse (DD-20) at:
- `GET /api/v1/admin/audit/{requestId}`
- `GET /api/v1/admin/audit/recent?tenantId=acme`

### 7a. Status view (DD-33)

```java
Optional<NotificationView> view = notificationService.find("acme", requestId);
Optional<NotificationView> byKey = notificationService.findByIdempotencyKey("acme", "billing", "order-42");
```

The view is the audit record joined with the latest delivery event: status, failure type, error code, scrubbed error message, provider message id, received/sent/delivered/failed/updated timestamps, recipient count, the caller's metadata, and `deliveryStatus` as text.
It never contains recipient addresses.
Both methods return empty while `notification.audit.enabled` is `false`.

---

## 8. OpenAPI / Swagger UI (Phase 9)

🟢 On in `notification-server`.
In library mode springdoc is optional in `notification-rest` since 1.1.0: add `org.springdoc:springdoc-openapi-starter-webmvc-ui` to your application (with `notification.rest.enabled: true`) to get the schema and the UI.

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
        <version>1.1.0</version>
    </dependency>
    <dependency>
        <groupId>com.github.ifrugal</groupId>
        <artifactId>notification-rest</artifactId>
        <version>1.1.0</version>
    </dependency>
    <dependency>
        <groupId>com.github.ifrugal</groupId>
        <artifactId>email-provider-smtp</artifactId>
        <version>1.1.0</version>
    </dependency>
</dependencies>
```

```yaml
notification:
  default-tenant: default
  rest:
    enabled: true                  # REST is off by default since 1.1.0
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
        <version>1.1.0</version>
    </dependency>
    <dependency>
        <groupId>com.github.ifrugal</groupId>
        <artifactId>notification-rest</artifactId>
        <version>1.1.0</version>
    </dependency>
    <dependency>
        <groupId>com.github.ifrugal</groupId>
        <artifactId>email-provider-ses</artifactId>
        <version>1.1.0</version>
    </dependency>
    <dependency>
        <groupId>com.github.ifrugal</groupId>
        <artifactId>sms-provider-twilio</artifactId>
        <version>1.1.0</version>
    </dependency>
</dependencies>
```

```yaml
notification:
  rest:
    enabled: true

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
    <version>1.1.0</version>
</dependency>
```

```yaml
notification:
  rest: { enabled: true }
  store: { type: redis }           # every enabled feature below moves to Redis
  rate-limit: { enabled: true, ... }
  retry: { enabled: true, ... }
  dead-letter: { enabled: true }
  webhooks: { enabled: true, twilio: { ... } }
  delivery-events: { enabled: true }

  redis:
    key-prefix: notification-svc
    dead-letter:  { max-entries: 10000 }
    delivery-events: { max-entries: 50000 }

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
| `notification.rest.enabled` | `false` | REST controllers, filters, exception handler, OpenAPI metadata, webhooks (since 1.1.0) |
| `notification.store.type` | `memory` | Store family for every enabled stateful feature: `memory`, `redis` or `jdbc` |
| `notification.rest.base-path` | `/api/v1` | Path prefix for all REST + admin + webhook surfaces |
| `notification.kafka.enabled` | `false` | Kafka consumer |
| `notification.audit.enabled` | `false` | Audit records in the store family's audit store, the data behind `NotificationService.find` (store shipped since 1.2.1, DD-33) |
| `notification.audit.retention-days` | `90` | Lifetime of an audit record (in-memory expiry, JDBC `expires_at`) |
| `notification.audit.max-entries` | `10000` | Bound of the in-memory audit store (since 1.2.1) |
| `notification.tenant-fallback.enabled` | `false` | A tenant without an entry under `notification.tenants` uses the providers of `notification.default-tenant` (since 1.2.1, DD-28) |
| `notification.providers.lazy-init` | `false` | Build providers on first use instead of at startup; a failed build fails the send with `PROVIDER_NOT_CONFIGURED` (`TRANSIENT`) (since 1.2.1, DD-29) |
| `notification.template.legacy-helpers` | `true` | Add the persistence-utils helpers `eval`, `file`, `js`, `serde_*` and leave `?new` unrestricted; deprecated, `false` from 2.0 (since 1.2.1, DD-26) |
| `notification.template.validate-before-dispatch` | `false` | Render before idempotency, rate limit and audit; refuse a template that cannot render with `TemplateRenderException` / HTTP 422 (since 1.2.1, DD-27) |
| `notification.recipient-guard.enabled` | `false` | Configuration-driven recipient guard (since 1.2.1, DD-34) |
| `notification.recipient-guard.channels.<channel>.allowed` | empty | `@domain`, exact address or phone number (digits) |
| `notification.recipient-guard.channels.<channel>.redirect-to` | unset | Replacement for not-allowed recipients; must itself be allowed; unset drops them |
| `notification.recipient-guard.channels.email.subject-prefix` | `[redirected: {originals}] ` | Subject prefix when something was redirected; empty disables |
| `notification.idempotency.enabled` | `true` | Idempotency dedup |
| `notification.idempotency.ttl` | `24h` | Key retention |
| `notification.idempotency.max-entries` | `100000` | In-memory bound |
| `notification.idempotency.retry-after-failure` | `true` | Deprecated since 1.2.1: alias of `failure-policy` (`true` = `RELEASE_ALL`, `false` = `KEEP`) (since 1.1.2) |
| `notification.idempotency.failure-policy` | unset (= `RELEASE_ALL`) | `RELEASE_ALL`, `KEEP` or `RELEASE_TRANSIENT`: what happens to a key after a failed send; replaceable with an `IdempotencyReleasePolicy` bean (since 1.2.1, DD-32) |
| `notification.idempotency.reject-key-reuse` | `false` | `409 KEY_REUSED` for a key reused with a different request fingerprint (since 1.2.1, DD-32) |
| `notification.idempotency.in-progress-timeout` | unset | Settle a key stuck `IN_PROGRESS` longer than this as `FAILED` / `IN_PROGRESS_TIMEOUT` / `AMBIGUOUS` and replay it (since 1.2.1, DD-32) |
| `notification.idempotency.store-scrubbed-error-message` | `false` | Keep the scrubbed error message of a stored `FAILED`/`REJECTED` response; `false` stores none, as in 1.2.0 (since 1.2.1, DD-32) |
| `notification.caller-registry.enabled` | `false` | Validate `X-Service-Id` against known list |
| `notification.caller-registry.strict` | `false` | Reject unknown callers with `403` |
| `notification.rate-limit.enabled` | `false` | Token-bucket throttling |
| `notification.rate-limit.default-rule.*` | — | Global bucket shape |
| `notification.rate-limit.by-channel.<name>.*` | — | Per-channel default (DD-23) |
| `notification.rate-limit.overrides[*]` | — | Per `(tenant, caller, channel)` overrides |
| `notification.rate-limit.<rule>.bandwidths[*]` | empty | Several `{capacity, refill-tokens, refill-period}` bands that must all allow; exclusive with the single-band fields (since 1.2.1, DD-30) |
| `notification.rate-limit.<rule>.scope` | `tenant-caller` | `tenant-caller`: bucket per `(tenant, caller, channel)`; `channel`: one bucket per channel shared by every request the rule matches (since 1.2.1, DD-30) |
| `notification.rate-limit.refund-unsent` | `false` | Refund the token of a failed send whose idempotency key the release policy released, unless `AMBIGUOUS` or `UNKNOWN` (since 1.2.1, DD-30, DD-32) |
| `notification.retry.enabled` | `false` | Synchronous retry on transient failures |
| `notification.retry.max-attempts` | `3` | Global attempts (incl. first try) |
| `notification.retry.max-retry-after` | `max-delay` | Longest provider `Retry-After` hint the executor waits for; a longer hint stops the retries and the failure goes to the DLQ (since 1.2.0, DD-25) |
| `notification.retry.by-channel.<name>.*` | — | Per-channel override (DD-23) |
| `notification.dead-letter.enabled` | `false` | DLQ recording |
| `notification.dead-letter.max-entries` | `1000` | In-memory bound |
| `notification.dead-letter.replay-lease` | `PT5M` | How long a replay holds its claim on a DLQ entry |
| `notification.webhooks.enabled` | `false` | `/webhooks/*` surface |
| `notification.webhooks.twilio.enabled` | `false` | Twilio status callbacks |
| `notification.webhooks.twilio.auth-token` | — | Required when verification on |
| `notification.webhooks.ses.enabled` | `false` | SES delivery callbacks via SNS |
| `notification.webhooks.ses.topic-arn` | — | Defense-in-depth topic match |
| `notification.delivery-events.enabled` | `false` | Persistent delivery event store |
| `notification.delivery-events.max-entries` | `5000` | In-memory bound |
| `notification.redis.key-prefix` | `notification-svc` | Namespace on shared Redis |
| `notification.redis.idempotency.enabled` | unset | Per-feature override of `store.type`: `true` Redis, `false` memory |
| `notification.redis.rate-limit.enabled` | unset | Per-feature override of `store.type`: `true` Redis, `false` memory |
| `notification.redis.dead-letter.enabled` | unset | Per-feature override of `store.type`: `true` Redis, `false` memory |
| `notification.redis.dead-letter.max-entries` | `1000` | Redis list cap |
| `notification.redis.delivery-events.enabled` | unset | Per-feature override of `store.type`: `true` Redis, `false` memory |
| `notification.redis.delivery-events.max-entries` | `10000` | Redis list cap |
| `notification.redis.audit.enabled` | unset | `false` keeps audit in memory under `store.type: redis` (since 1.2.1) |
| `notification.store.jdbc.schema` | none | Schema of the JDBC tables; unset uses the search path |
| `notification.store.jdbc.table-prefix` | `notification_` | Prefix of every JDBC table, constraint and index |
| `notification.store.jdbc.datasource-bean-name` | none | DataSource bean for the JDBC stores; unset picks the single or `@Primary` one |
| `notification.store.jdbc.dead-letter-retention` | `P30D` | Lifetime of a dead-letter row |
| `notification.store.jdbc.delivery-event-retention` | `P30D` | Lifetime of a delivery-event row |
| `notification.store.jdbc.purge.enabled` | `false` | Scheduled purge of expired JDBC rows |
| `notification.store.jdbc.purge.interval` | `PT1H` | Delay between purge runs |
| `notification.store.jdbc.purge.batch-size` | `1000` | Rows deleted per purge statement |
| `notification.health.dlq-near-full-percent` | `80` | DLQ → `OUT_OF_SERVICE` threshold |

`<rule>` is `default-rule`, `by-channel.<channel>` or `overrides[i]`.

---

## See also

- [README](../README.md) — landing page, quick start, REST/Kafka examples
- [ARCHITECTURE.md](./ARCHITECTURE.md) — system overview, send-path lifecycle, SPI catalogue
- [`docs/design-decisions/`](./design-decisions/) - every DD with full rationale
- [CHANGELOG.md](../CHANGELOG.md) — version history
