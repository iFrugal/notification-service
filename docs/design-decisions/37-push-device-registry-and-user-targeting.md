# Decision 37: Push Device Registry, User Targeting, Token Cleanup and Push Templates

## Status: DECIDED

## Context

Up to 1.3.0 the FCM provider (DD-24) sent to whatever device tokens the caller named.
Every application that sends push had to build the same pieces around it:

- a table of each user's devices, with the shared-phone case (one token moving from one user to another), a cap per user and staleness;
- the expansion of "send to this user" into that user's tokens before every send;
- the deletion of a token once FCM reported it as unregistered, from the `BOUNCED` / `INVALID_TARGET` delivery event that only carries a hash of the token;
- a way to set a push title and data from a template: the engine rendered only a body for push, and the title came from `PushRecipient.title`.

Doing the expansion outside the library has a cost beyond duplication.
The idempotency record, the audit record and a dead letter then hold device tokens instead of a user, and a replay sends to the tokens of the day the request was first made.

## Decision

Everything is opt-in and additive: without `notification.push.devices.enabled` and without a registry bean, the only change is that push templates may use sections.

### Device registry SPI (`notification-api`, package `api.push`)

- `PushDeviceRegistry`: `register`, `unregister(userId, installationId)`, `unregisterToken`, `unregisterAll(userId)`, `activeDevices(userId, appId)`, `removeByTokenHash(tokenHash, reason)` and `purgeStale(olderThan)`.
- `PushDeviceRegistration` (the input; values stripped and checked against the column lengths) and `PushDevice` (the stored device), both with a `toString()` that shows the token hash only; `PushPlatform` (`ANDROID`, `IOS`, `WEB`).
- `PushTokens.hash(token)` is now the one definition of `sha256:<first 16 hex of SHA-256(token)>`; the FCM module delegates to it with unchanged output.
- `PushDeviceRemovalReasons`: `unregistered`, `moved`, `evicted`, `invalid_target`, `stale`.

Semantics every implementation shares, pinned by `PushDeviceRegistryContractTest` (in the notification-core test-jar, run against both implementations):

- **Scope.** Every operation works in the `TenantContext` tenant, or in the pool scope (no tenant) when none is set; `register` uses the registration's own `tenantId` when it names one.
  A device in one scope is invisible in every other, and the same token may exist in two scopes.
- **Register** is an upsert on `(scope, userId, installationId)`, in one transaction: remove the same token from any other user or installation in the scope (`moved`, the shared phone), insert or refresh the device (`lastSeenAt` is now; a new token for the same installation is a token rotation), then remove the user's least recently seen devices beyond `max-devices-per-user` (`evicted`).
  The device just registered always stays.
- **Active devices** are those seen within `stale-after`, most recently seen first.
- **Unregister** operations are idempotent.
- **Every removal** is logged at INFO with the reason only, never the token or its hash (a hash is still a stable identifier of a person's device).

Two implementations:

| | Where | Notes |
|--|--|--|
| `InMemoryPushDeviceRegistry` | notification-core | One lock, lost on restart; tests, development, single instance |
| `JdbcPushDeviceRegistry` | notification-store-jdbc | The `<prefix>push_device` table; no Redis variant, because devices are durable data |

There is no Redis registry: with the Redis store family and `notification.push.devices.enabled=true`, startup fails unless the application declares a `PushDeviceRegistry` bean.

### JDBC table

`<prefix>push_device` follows the store conventions: a nullable `tenant_id`, a generated `tenant_key` (`COALESCE(tenant_id, '')`), JSON as `TEXT`, database time, an `expires_at` and a batch purge through `JdbcStorePurger`.

| Item | Definition |
|------|------------|
| Columns | `id`, `tenant_id`, `tenant_key`, `user_id`, `installation_id`, `app_id`, `platform`, `token`, `token_hash`, `token_sha256`, `locale`, `timezone`, `attributes`, `created_at`, `last_seen_at`, `expires_at` |
| Unique | `(tenant_key, user_id, installation_id)` and `(tenant_key, token_sha256)` |
| Indexes | `(tenant_key, user_id, last_seen_at)`, `(tenant_key, token_hash)`, `(expires_at)` |

- `token_sha256` is the full SHA-256 of the token, so a token of up to 4096 characters is never indexed itself.
- `expires_at` is `last_seen_at + purge-after` (default 270 days); an expired device is invisible and the purger deletes it.
- `register` takes two transaction-scoped advisory locks, on the scope and user and then on the scope and token, always in that order.
  Two registrations of one user cannot both miss each other's eviction, and two users registering the same token cannot both insert it; a unique-key collision that still happens is retried, up to three attempts.
- The library still runs no DDL; the table is in `JdbcStoreSchema` and `db/postgresql/notification-store.sql`.
  Under row-level security the table behaves like the others; `JdbcStoreRlsIT` covers it.

### User targeting

- `PushRecipient` gains nullable `userId` and `appId` (omitted from JSON when null, 1.2 constructor kept), `PushRecipient.forUser(userId[, appId])`, `isUserTarget()` and `withDeviceTokens(tokens)`.
  `userId` is a sixth target kind, exclusive with the other five.
- A new core step, `PushTargetExpansion`, runs in `DefaultNotificationService` after the idempotency claim, the rate limit, the recipient guard, the audit record and rendering, and before the provider is resolved.
  It is wired through `NotificationServiceExtensions.pushTargets` (with `withPushTargets`), not a new constructor parameter.
- It replaces the user target with the `deviceTokens` of the user's active devices in the current tenant scope, and sets the request metadata `push.multi-token-policy` from `notification.push.user-targets.multi-token-policy` (default `any`) unless the caller set it.
  Only the request handed to the provider carries tokens; the request the caller sent, which the idempotency record, the audit record and a dead letter keep, still names the user, so a replay resolves the devices again.
- Failures are `FAILED` and `PERMANENT`, without a provider call:

| Error code | When | Dead letter |
|------------|------|-------------|
| `NO_DEVICES` | The user has no active device (for `appId`, when given) | No: a replay cannot succeed until the user registers a device |
| `PUSH_USER_TARGETING_DISABLED` | No registry is configured | Yes: a configuration problem |
| `PUSH_INVALID_USER_TARGET` | `userId` together with another target | Yes |

- User targeting is on exactly when a `PushDeviceRegistry` bean exists.
- The FCM provider reads `push.multi-token-policy` per request (`any` or `all`, else `FCM_INVALID_OVERRIDE`) and refuses an unresolved `userId` with `FCM_INVALID_TARGET_SPEC`.
- Pre-claim validation (`validate-before-dispatch`, DD-27) does not look at the recipient, so a user target passes it unchanged.

### Invalid-token cleanup

`PushDeviceCleanupListener`, a `DeliveryEventListener`, is registered whenever a registry exists, unless `notification.push.devices.cleanup-on-invalid-target=false`.
On a `BOUNCED` event with reason `INVALID_TARGET` and a well-formed `tokenHash` attribute it calls `removeByTokenHash(hash, "invalid_target")` in the current tenant scope; every other event is ignored, and a registry failure is logged, never thrown.
FCM publishes the event on the sending thread, so the scope is the one the send ran in.
The send itself still succeeds when another device accepted (policy `any`).

The hash keeps 64 bits, so two tokens of one scope share it with a probability of about `n / 2^64` per bounce for `n` devices: about 5 in 100 trillion at a million devices.
The cost of such a collision is one device registering again.

### Push templates

`PushContentRenderer`, a `ChannelContentRenderer` for `PUSH` (DD-35's hook), recognises optional sections:

```
[TITLE]Order ${orderId} shipped[/TITLE]
[BODY]It arrives on ${eta}.[/BODY]
[DATA key="orderId"]${orderId}[/DATA]
```

- The template is found and rendered by `NotificationTemplateEngine.renderRaw`: the engine's own lookup (tenant, then default), cache, helpers and FreeMarker configuration.
  The renderer never loads a template itself, so any template source the engine is given, including a future database-backed loader, serves push too.
- Before rendering, each marker in the template source is swapped for a token built from a fresh random UUID, as the email sections are; the output is split on those tokens only, so a marker inside template data is plain text.
  Stored templates keep the literal markers.
- No section: the output is the body, exactly as before.
  With sections: title, body (else the text outside the sections) and data, all trimmed, plain text, nothing HTML-escaped.
- The result is `RenderedContent(subject = title, textBody = body, payload = PushContent(title, body, data))`.
  FCM uses the rendered title before `PushRecipient.title` (as before) and merges the rendered data under the caller's `data` (the caller's key wins; reserved FCM keys are still refused).
- A section opened inside another, a close without an open, a section left open, a repeated section or data key, or a data key that is not 1-128 letters, digits, `.`, `_` or `-` is a `TemplateRenderException` with reason `INVALID_TEMPLATE`: the template source is at fault.
- On by default (`notification.push.template-sections.enabled`); an application with its own `PUSH` renderer turns it off.

### Keyless FCM credentials

`push-provider-fcm-google-auth` adds `credentials: external-account-supplier:<name>`: workload identity federation built with google-auth-library's `IdentityPoolCredentials` and an `IdentityPoolSubjectTokenSupplier`, with no credential file.

- `FcmSubjectTokenSupplier` is the cloud-neutral SPI: `name()`, `audience()`, `subjectTokenType()` (JWT by default), `serviceAccountEmail()` or `serviceAccountImpersonationUrl()`, `tokenUrl()` (Google STS by default) and `subjectToken()`.
- `FcmGoogleAuthAutoConfiguration` declares `GoogleAuthTokenProviderFactory` as a bean with every supplier bean; the FCM provider asks bean factories before the `ServiceLoader` ones, so it wins.
  Two suppliers with one name fail startup; an unknown name fails the tenant's configuration naming the known ones.
- The supplier itself, for example an Azure managed identity token for an Entra application, lives in the application.

### Native image

- `FcmRuntimeHints` (imported by the FCM auto-configuration): the `META-INF/services` file of `FcmAccessTokenProviderFactory` and public constructors of both shipped factories (the google-auth one by name).
  The FCM JSON is built and read as Jackson trees, so no DTO needs binding hints.
- `JdbcStoreRuntimeHints` also binds `PushDevice`; `PushRecipient` was already bound and its new components come with it.
- `RuntimeHintsPredicates` tests cover the FCM hints; a host's native build is the end-to-end proof.

## Configuration

| Property | Default | Meaning |
|----------|---------|---------|
| `notification.push.devices.enabled` | `false` | Register the in-memory or JDBC registry for the selected store family |
| `notification.push.devices.max-devices-per-user` | `10` | Devices one user keeps per scope |
| `notification.push.devices.stale-after` | `60d` | A device not seen for this long gets no notifications |
| `notification.push.devices.purge-after` | `270d` | A device not seen for this long expires (JDBC) |
| `notification.push.devices.cleanup-on-invalid-target` | `true` | Register `PushDeviceCleanupListener` |
| `notification.push.user-targets.multi-token-policy` | `any` | `any` or `all` for user-addressed sends |
| `notification.push.template-sections.enabled` | `true` | Register `PushContentRenderer` |

## Out of scope

- Notification preferences and quiet hours: product data that belongs to a router's gating SPI, not designed from one channel.
- Topics per device and topic subscription management.
- REST endpoints for device registration: the host owns authentication and its path shape.
- A Redis registry.

## Reasoning

### Why expansion in the library rather than in the caller

The caller would need the registry anyway, and doing the expansion there puts tokens into the idempotency record, the audit record and dead letters.
In the library the stored request names the user, a replay resolves the current devices, and a user-addressed push is just a `NotificationRequest` that any future router can send.

### Why `userId` on `PushRecipient` instead of a new recipient type

A new subtype of the sealed `Recipient` would touch the permits list, the Jackson subtypes, PII masking, the audit recipient count and the native hints, for no behaviour that the record cannot carry.

### Why `NO_DEVICES` is not dead-lettered

A dead letter is for an operator to act on.
A user without a device is a normal state, and replaying the request cannot succeed until the user registers one.

### Why the scope is `TenantContext`, not the request's tenant

The request's tenant falls back to `notification.default-tenant`, which would put every pool-scope device under a tenant named `default`.
The application decides whether devices are per organisation or per person by what it puts in `TenantContext`, exactly as it does for the other stores.

### Why advisory locks in the JDBC registry

`SERIALIZABLE` would make every concurrent registration of one user a retry, and row locks cannot lock rows that do not exist yet.
Two advisory locks taken in a fixed order serialise exactly the registrations that conflict and cannot deadlock.

### Why the push renderer goes through the engine

A database-backed template loader is being designed to plug into the engine's lookup.
Rendering push templates through `renderRaw` means push gets that loader with no change, and the anti-injection tokens stay an internal step of rendering.
