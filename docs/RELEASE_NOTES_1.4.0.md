# notification-service 1.4.0

1.4.0 builds on 1.3.0: everything in the [1.3.0 release notes](./RELEASE_NOTES_1.3.0.md) applies, and a 1.2 deployment upgrading to 1.4.0 should read those and the [1.2.1 notes](./RELEASE_NOTES_1.2.1.md) first.
Push reuses 1.3.0's per-channel content renderer hook and `RenderedContent.payload`.

## Highlights

- **A push device registry.**
  `PushDeviceRegistry` keeps each user's devices: registration with token rotation, the shared-phone case (a token registered by another user moves to them), a cap per user that evicts the least recently seen device, and staleness (DD-37).
  It ships in memory (notification-core) and in PostgreSQL (`notification-store-jdbc`, table `<prefix>push_device`), behind `notification.push.devices.enabled`.
- **Push to a user.**
  `PushRecipient.forUser(userId)` sends to the user's active devices.
  The library resolves the devices after the idempotency claim and the rate limit, so the idempotency record, the audit record and dead letters keep the user, never the tokens, and a replay reaches the user's current devices.
  A user without an active device gets `FAILED` / `NO_DEVICES`, with no provider call and no dead letter.
- **Dead tokens clean themselves up.**
  When FCM reports a token as unregistered, `PushDeviceCleanupListener` removes the device from the registry, in the tenant scope the send ran in.
- **Push templates with a title and data.**
  `[TITLE]`, `[BODY]` and `[DATA key="..."]` sections in a push template set the notification title, body and data; a template without sections renders as before.
  Templates are found through the engine's normal lookup, and a marker that arrives in template data is plain text.
- **Keyless FCM credentials.**
  `credentials: external-account-supplier:<name>` federates into Google Cloud with a subject token from an `FcmSubjectTokenSupplier` bean (for example an Azure managed identity token), with no key and no credential file.
- **Native image hints for FCM.**
  `FcmRuntimeHints` keeps the token provider factories reachable through `ServiceLoader` in a native image.

## New modules

None: the registry is in `notification-core` and `notification-store-jdbc`, the supplier SPI in `push-provider-fcm-google-auth`.

## Behaviour changes

The upgrade is additive: without `notification.push.devices.enabled`, without a `PushDeviceRegistry` bean and without new credentials, nothing behaves differently, with one exception.

- **Push templates may use sections.**
  `PushContentRenderer` is registered for `PUSH` by default (`notification.push.template-sections.enabled`, default `true`).
  A push template that contains the literal text `[TITLE]`, `[/TITLE]`, `[BODY]`, `[/BODY]`, `[/DATA]` or `[DATA key="..."]` is now parsed into sections; any other template renders exactly as before.
  Malformed sections (unclosed, nested, repeated, or a bad data key) fail with `TemplateRenderException` reason `INVALID_TEMPLATE` (`TEMPLATE_INVALID`).
  An application that declared its own `ChannelContentRenderer` for `PUSH` in 1.3.0 must set `notification.push.template-sections.enabled=false`, or startup fails with two renderers for one channel.

## API additions and compatibility

Every change to the public API is additive: no type, method or constructor was removed or changed its signature.
Two additions can still break source code:

- **`PushRecipient`** gains two components, `userId` and `appId`, both omitted from JSON when null.
  The 1.1 (11-argument) and 1.2 (13-argument) constructors are kept, so constructor calls compile and behave as before, and JSON written by 1.3 reads unchanged.
  A record pattern that deconstructs `PushRecipient(...)` with 13 components no longer compiles.
- **`NotificationServiceExtensions`** gains a seventh component, `PushTargetExpansion pushTargets`.
  The 1.3.0 6-argument and the 1.2.1 5-argument constructors, `defaults()` and `of(...)` are kept and mean "no user targeting"; a 6-component record pattern no longer compiles.

Other additions:

- `com.lazydevs.notification.api.push`: `PushDeviceRegistry`, `PushDevice`, `PushDeviceRegistration`, `PushPlatform`, `PushDeviceRemovalReasons`, `PushTokens` (the one definition of the `sha256:<16 hex>` token hash), `PushTargeting` (error codes and the `push.multi-token-policy` metadata key) and `PushContent` (the push renderer's payload).
- `PushRecipient.forUser(userId[, appId])`, `isUserTarget()` and `withDeviceTokens(List)`.
- `com.lazydevs.notification.core.push`: `InMemoryPushDeviceRegistry`, `PushTargetExpansion`, `PushDeviceCleanupListener`, `PushContentRenderer`.
- `NotificationTemplateEngine.renderRaw(request, prepareSource)`: renders a request's template through the engine's lookup after a source rewrite, for channel renderers that parse their own sections.
- `StoreFeature.PUSH_DEVICES`.
- `JdbcPushDeviceRegistry`, `JdbcStoreTables.pushDevice()` and the `push_device` table in `JdbcStoreSchema`.
- `FcmSettings.CREDENTIALS_EXTERNAL_ACCOUNT_SUPPLIER`, `FcmRuntimeHints`.
- `FcmSubjectTokenSupplier`, `FcmGoogleAuthAutoConfiguration` and a `GoogleAuthTokenProviderFactory(List<FcmSubjectTokenSupplier>)` constructor in `push-provider-fcm-google-auth`.
- `notification-core` publishes a test-jar with `PushDeviceRegistryContractTest`, the semantics every registry must meet; run it against your own registry.

New error codes:

| Code | Where | Meaning |
|------|-------|---------|
| `NO_DEVICES` | send response | The user has no active device; `PERMANENT`, no dead letter |
| `PUSH_USER_TARGETING_DISABLED` | send response | A user target without a registry |
| `PUSH_INVALID_USER_TARGET` | send response | `userId` with another target |

## Upgrade notes

- **JDBC: add the table before enabling the registry.**
  Run the `CREATE TABLE notification_push_device` and its three `CREATE INDEX` statements from `db/postgresql/notification-store.sql` (or `JdbcStoreSchema.postgresql(tables).ddl()` for your prefix and schema), and grant the application role `SELECT, INSERT, UPDATE, DELETE` on it.
  Nothing else in the schema changes.
- **Choose the device scope.**
  Every registry operation and the expansion of a user target work in the `TenantContext` tenant, or in the pool scope when none is set.
  Keep devices per person by leaving `TenantContext` unset around registration and the send; under row-level security, the policy must then admit `tenant_id IS NULL` rows for the role that manages devices.
- **The Redis store family has no registry.**
  With `notification.push.devices.enabled=true` and Redis selected for it, startup fails unless you declare your own `PushDeviceRegistry` bean.
- **Remove your own dead-token listener** once the registry holds the tokens: `PushDeviceCleanupListener` does the same job (switch it off with `notification.push.devices.cleanup-on-invalid-target=false` if you keep yours).
- **Roll every node to 1.4.0 before sending user-addressed push.**
  A 1.3 node that replays a stored request with a `userId` ignores the unknown property and sends to no target, which fails with `FCM_INVALID_TARGET_SPEC`.

## Configuration to set

| Property | Default | Description |
|----------|---------|-------------|
| `notification.push.devices.enabled` | `false` | Register the in-memory or JDBC device registry |
| `notification.push.devices.max-devices-per-user` | `10` | Devices one user keeps per scope; the least recently seen are evicted |
| `notification.push.devices.stale-after` | `60d` | A device not seen for this long gets no notifications |
| `notification.push.devices.purge-after` | `270d` | JDBC: a device not seen for this long expires and is purged with the other stores |
| `notification.push.devices.cleanup-on-invalid-target` | `true` | Remove a device when FCM reports its token as invalid |
| `notification.push.user-targets.multi-token-policy` | `any` | `any` or `all` for a send addressed to a user |
| `notification.push.template-sections.enabled` | `true` | Register the push sections renderer |

Per request, the metadata key `push.multi-token-policy` (`any` or `all`) overrides the tenant's FCM `multi-token-policy`.
Per FCM tenant, `credentials: external-account-supplier:<name>` selects a subject token supplier bean.

## Verification

- `PushDeviceRegistryContractTest` runs against the in-memory registry in the unit tests and against PostgreSQL in `JdbcPushDeviceRegistryIT` (Testcontainers), with concurrent registrations and the batch purge; `JdbcStoreRlsIT` covers the table under row-level security.
- The send-flow tests cover user targets, `NO_DEVICES` without a provider call or a dead letter, dead letters that keep the user, and a caller's multi-token policy.
- The FCM tests cover the per-request policy, an unresolved user target, and template data merged under the caller's data.
- The google-auth tests run the supplier form against a local STS and IAM stub, with and without impersonation.
