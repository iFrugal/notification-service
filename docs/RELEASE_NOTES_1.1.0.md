# notification-service 1.1.0

## Highlights

- **PostgreSQL stores.**
  The new `notification-store-jdbc` module backs idempotency, the dead-letter queue and delivery events with plain SQL over `JdbcClient`, for multi-pod deployments without Redis.
- **Azure Communication Services Email.**
  The new `email-provider-acs` module adds `acs` as a built-in email provider.
- **One switch for store families.**
  `notification.store.type` (`memory`, `redis`, `jdbc`) selects where every enabled stateful feature keeps its state, and startup fails if the selected module is missing.
- **Replica-safe dead-letter replay.**
  Single and batch replay now claim entries before sending, remove them on success and release them on failure, so two replicas never send the same JDBC-backed entry twice.
- **Starter hygiene.**
  Every module wires itself through Spring Boot auto-configuration; the starter no longer component-scans, and the REST API is opt-in.

## New modules

| Module | Coordinates | What it is |
|--------|-------------|------------|
| `notification-store-jdbc` | `com.github.ifrugal:notification-store-jdbc:1.1.0` | PostgreSQL-backed `IdempotencyStore`, `DeadLetterStore` and `DeliveryEventStore`. Reference DDL ships in the jar; the host runs the migrations. No rate limiter. See the [module README](../notification-store-jdbc/README.md). |
| `email-provider-acs` | `com.github.ifrugal:email-provider-acs:1.1.0` | Azure Communication Services Email, authenticated by connection string, `DefaultAzureCredential` (with `azure-identity`) or a `TokenCredential` bean. See the [module README](../notification-channels/notification-channel-email/email-provider-acs/README.md). |

## Breaking changes

- **REST is off by default.**
  The REST controllers, the tenant and caller filters and the exception handler register only with `notification.rest.enabled=true` (in a servlet application with `notification-rest` on the classpath).
  The standalone server sets it in its own `application.yml`.
- **Webhooks require REST.**
  `notification.webhooks.enabled=true` has no effect unless `notification.rest.enabled=true`.
- **Filters are scoped to the REST base path.**
  `TenantFilter` and `CallerAdmissionFilter` run only under `<notification.rest.base-path>/*` (default `/api/v1/*`), and the exception handler only advises the notification controllers, so neither touches the host's own endpoints.
- **springdoc is optional in `notification-rest`.**
  A library-mode host that wants `/v3/api-docs` and Swagger UI adds `org.springdoc:springdoc-openapi-starter-webmvc-ui` itself; the standalone server still includes it.
- **The starter no longer component-scans.**
  Every bean comes from a module's own auto-configuration.
  If you placed your own beans under `com.lazydevs.notification.*` and relied on the starter's scan to find them, register them yourself.
- **`notification.store.type` replaces `notification.redis.enabled`.**
  `notification.redis.enabled` was never read and is no longer bound; startup logs a warning when it is set.
  Select Redis with `notification.store.type=redis`.
- **The feature flag is the master switch.**
  Enabling a Redis per-feature flag (`notification.redis.<feature>.enabled=true`) no longer switches the feature on; it only selects Redis for a feature that `notification.<feature>.enabled` has enabled.
  An explicit `false` keeps that feature in memory.
- **`Class.forName` provider registration is removed.**
  Built-in providers come from their module's auto-configuration as prototype beans named `<name><Channel>Provider` (for example `smtpEmailProvider`).
  A provider that is configured but whose module is missing fails startup with a message naming the artifact to add.
- **Six empty artifacts are no longer published:** `sms-provider-sns`, `whatsapp-provider-twilio`, `whatsapp-provider-meta`, `push-provider-fcm`, `push-provider-apns` and `notification-audit`.
  Their 1.0.x jars contained no classes; remove the dependencies.
- **Minimum Spring Boot 4.1.**
  Already the case in 1.0.2; new if you upgrade from 1.0.1 or earlier.
- **Health indicators follow bean presence.**
  The `dlq`, `idempotency`, `rateLimit` and `deliveryEvents` indicators register whenever the corresponding store or limiter bean exists, including your own beans, rather than on the feature property.
- **Metrics need a `MeterRegistry` bean.**
  Micrometer on the classpath is not enough; Spring Boot actuator's metrics auto-configuration provides the registry.

## SPI changes

- **`DeadLetterStore.claim` and `release`, additive with defaults.**
  `claim(tenantId, limit, lease)` leases up to `limit` entries of a tenant, `claim(tenantId, requestId, lease)` leases one entry, and `release(tenantId, requestId)` gives a lease back; `remove` acknowledges.
  The defaults take no lease, so existing implementations compile and behave as before; a store that shares state across replicas should override all three.
  `JdbcDeadLetterStore` implements them with `FOR UPDATE SKIP LOCKED`.
- **Provider SPIs are unchanged.**
  `EmailProvider`, `SmsProvider`, `WhatsAppProvider` and `PushProvider` keep their 1.0 shape, so the planned FCM (HTTP v1) and Meta WhatsApp Cloud API providers are not blocked by this release.

## Fixes

- **Redis stores could not activate in starter mode.**
  The 1.0.x starter never scanned the Redis package; `notification-redis` now has its own auto-configuration.
- **`LoggingDeliveryEventListener` removed itself.**
  Its `@ConditionalOnMissingBean(DeliveryEventListener.class)` matched its own bean definition; it now lives in the REST auto-configuration and backs off only for another listener or a `DeliveryEventStore`.
- **Header case.**
  `X-Tenant-Id` and `X-Service-Id` sent in any spelling other than all-lowercase were ignored over HTTP/1.1, so a strict caller registry let an unknown caller through and requests without a body `tenantId` fell back to the default tenant.
  Header names are now matched case-insensitively, as are `x-user-id`, `x-role` and `x-request-id`; a client's `X-Request-Id` is echoed back instead of a generated one.
- **Double send on concurrent replay.**
  Two replicas, or two operators, replaying the same dead-letter entry could both send it.
  Replay now claims the entry first (`notification.dead-letter.replay-lease`, default `PT5M`); a single replay of an entry held elsewhere returns `409` with `status: CLAIMED`, and a batch lists such entries as `CLAIMED` under a new `claimed` count.
  The JDBC store enforces this across replicas; the in-memory and Redis stores keep the lock-free SPI defaults.

Already released in 1.0.2, listed for anyone upgrading from 1.0.1:

- **Default stores were never registered, so idempotency was silently off.**
  The in-memory defaults are now `@Bean` methods in an auto-configuration, where `@ConditionalOnMissingBean` works.
- **Twilio 12.1.1 test compile.**
  `TwilioFailureClassifierTest` adapted to the Twilio 12.1.1 `ApiException`.
- **Jackson single version.**
  The parent imports `jackson-bom`, so every Jackson 2 artifact resolves at one version.

## Configuration to set

| Property | Default | When to set it |
|----------|---------|----------------|
| `notification.rest.enabled` | `false` | Set `true` to expose the REST API, its filters and webhooks. |
| `notification.store.type` | `memory` | `redis` (needs `notification-redis`) or `jdbc` (needs `notification-store-jdbc`) for shared state across pods. |
| `notification.store.jdbc.schema` | none | Schema of the JDBC tables; unset uses the connection's search path. |
| `notification.store.jdbc.table-prefix` | `notification_` | Prefix of every JDBC table, constraint and index name. |
| `notification.store.jdbc.datasource-bean-name` | none | The `DataSource` bean for the JDBC stores when the application has several and none is `@Primary`. |
| `notification.store.jdbc.purge.enabled` | `false` | Set `true` to let the module purge expired JDBC rows on a schedule (`purge.interval`, default `PT1H`). |
| `notification.dead-letter.replay-lease` | `PT5M` | How long a replay holds its claim on a dead-letter entry; keep it longer than your slowest send, including retries. |
| `notification.tenants.<tenant>.channels.email.providers.acs.properties.*` | see below | Per-tenant ACS settings. |

Per-tenant ACS keys (under `providers.acs.properties`):

| Key | Default | Description |
|-----|---------|-------------|
| `connection-string` | - | ACS connection string. Wins over `endpoint` when both are set. |
| `endpoint` | - | ACS resource endpoint, used together with `credential`. |
| `credential` | - | `default` for `DefaultAzureCredential` (needs `azure-identity`), otherwise the name of a `TokenCredential` bean. Blank with `endpoint` uses the application's only `TokenCredential` bean. |
| `sender` | channel `from-address` | Sender address; a MailFrom address of a domain connected to the ACS resource. Required. |
| `reply-to` | - | Default reply-to addresses, comma-separated or a YAML list. |
| `send-mode` | `wait` | `wait` polls until ACS reports a final status; `submit` returns once ACS accepted the message. |
| `wait-timeout` | `PT60S` | Upper bound for `wait` mode. |
| `sdk-retries` | `0` | Retries inside the Azure SDK; keep `0` so the library's `RetryExecutor` is the only retry layer. |
| `user-engagement-tracking-disabled` | ACS resource setting | `true` disables open and click tracking for this tenant. |
