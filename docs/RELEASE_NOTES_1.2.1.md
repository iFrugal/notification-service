# notification-service 1.2.1

This release was published as 1.2.1, although its additive API changes would normally make it a minor release; the artifacts on Maven Central are 1.2.1.
## Highlights

- **Responses say why a send failed.**
  A `FAILED` response now carries `failureType` (`TRANSIENT`, `PERMANENT`, `AMBIGUOUS`, `UNKNOWN`), the provider's `providerMetadata` and its `retryAfter` hint (for example an HTTP 429 `Retry-After`), each left out of the JSON when unset (DD-31).
- **Safer idempotency.**
  Keys carry a request fingerprint, so a key reused for a different message can be refused with `409 KEY_REUSED`.
  `failure-policy=RELEASE_TRANSIENT` releases a key only after a failure that cannot have delivered and replays the others, and the decision is a replaceable `IdempotencyReleasePolicy` bean.
  `in-progress-timeout` frees a key held by a dead process as an `AMBIGUOUS` failure instead of answering 409 for 24 hours (DD-32).
- **Status view.**
  `NotificationService.find(tenantId, requestId)` and `findByIdempotencyKey(...)` answer "what happened to this send?" from an in-memory or JDBC audit store that now ships with the library (DD-33).
- **Recipient guard.**
  A non-production environment can allow-list recipients per channel and redirect or drop everything else, with an SPI to replace the rules (DD-34).
- **The template engine owns its FreeMarker configuration.**
  `<#include>` and `<#import>` resolve against `notification.template.base-path`, relative to the including template, and cannot leave it; render errors are thrown once as `TemplateRenderException` with a reason and the variable path (DD-26).
  Existing templates render exactly as before.
- **Validate before dispatch.**
  With `notification.template.validate-before-dispatch: true`, a request whose template cannot render is refused with HTTP 422 before anything is stored, rate limited or dead-lettered, and a valid request is still rendered only once (DD-27).
- **The file-reading and code-running template helpers can be switched off.**
  `notification.template.legacy-helpers: false` removes `eval`, `file`, `js` and `serde_*` and restricts `?new` (DD-26).
- **Tenants without their own configuration can share the default tenant's providers** with `notification.tenant-fallback.enabled` (DD-28).
- **An application can boot before its provider settings exist** with `notification.providers.lazy-init`: providers are built on first use, and one that cannot be built fails its sends as a retryable `PROVIDER_NOT_CONFIGURED` (DD-29).
- **Rate limits with several bands, account-wide buckets and refunds.**
  A rule can list `bandwidths` that must all allow (for example 2 per minute and 5 per hour), share one bucket per channel with `scope: channel`, and give a token back for a failed send whose idempotency key was released (DD-30).

## Behaviour changes

With default settings a 1.2.0 deployment behaves as before, apart from the items marked **default**.

- **default: Render failures have template error codes.**
  A template that fails to render is still a `FAILED` response, stored in the audit and dead-letter records, but its error code is `TEMPLATE_INVALID` or `TEMPLATE_MODEL_INVALID` instead of `INTERNAL_ERROR`, its dead-letter `failureType` is `PERMANENT` instead of `UNKNOWN`, and it is logged as one "Notification error" line without a stack trace.
  A helper that throws (for example `formatCurrency` with an unknown currency code) is reported the same way.
- **default: FreeMarker no longer logs render errors** to `freemarker.runtime`; with `legacy-helpers: true`, errors inside `eval` and `file` are still logged by the persistence-utils configuration they render through.
- **default: Includes and imports resolve against `base-path`.**
  Templates that included files from persistence-utils' own locations (`classpath:/templates/` or `./templates`) while `base-path` points elsewhere must move those files under `base-path`.
  Included names whose segments do not pass the template id rule, or that use `..` to leave `base-path`, are not found and logged at WARN.
- **default: `.current_template_name` and `.main_template_name`** return the template path relative to `base-path` (for example `default/email/WELCOME.ftl`) instead of an empty string.
- **default: Startup WARN** while `notification.template.legacy-helpers` is `true` (the default), naming the deprecated helpers.
- **default: Template cache settings also cover included templates**, and clearing the template cache for one tenant also clears the include cache completely.
- **default: `FAILED` responses carry `failureType`, and `providerMetadata` / `retryAfter` when the provider gave them**, including replays; successful responses are unchanged.
- **default: The audit service receives outcomes through `NotificationAuditService.recordOutcome(NotificationResponse)`.**
  Its default calls `updateStatus`, so existing audit implementations see the same calls.
  The error message passed is scrubbed by the `MessageScrubber` (email addresses become `[email]`, phone numbers `[phone]`) on top of the existing `PiiMasking.redact`.
- **default: `notification.audit.enabled=true` now works without an application bean** for `store.type` `memory` and `jdbc`, storing records in the shipped audit store; with `redis` and no bean, startup still fails, now with a message naming the options.
- **default: JDBC idempotency store probes for the `fingerprint` column at startup.**
  On a 1.2.0 table it logs one WARN with the `ALTER TABLE` statement and keeps working without fingerprints.
- **ACS keeps the DD-25 classification.**
  Timeouts and I/O errors stay `TRANSIENT` (ACS de-duplicates a resend on its `Operation-Id`) and HTTP 401/403 stay `PERMANENT`.
  `timeout-failure-type: ambiguous` opts a tenant in to `AMBIGUOUS` for read timeouts, resets and `TimeoutException`.
- **Stored idempotency failures still have no error message**, as in 1.1.2 to 1.2.0; `notification.idempotency.store-scrubbed-error-message=true` keeps a scrubbed one.
- With `notification.tenant-fallback.enabled=true`, a request for an unconfigured tenant no longer fails with `TENANT_NOT_FOUND` when the default tenant is configured.
- With `notification.providers.lazy-init=true`, provider configuration errors are no longer reported at startup; they fail the first send with `PROVIDER_NOT_CONFIGURED` and `FailureType.TRANSIENT`, and `GET /admin/health` lists the provider as `NOT_INITIALIZED` or `INIT_FAILED` (the latter makes the status `DEGRADED`).
- `Bucket4jRateLimiter.snapshot()` lists a `scope: channel` bucket under the key `("*", <ruleId>, <channel>)`; in Redis it lives at `<prefix>:ratelimit-shared:<ruleId>:<channel>`, and tenant buckets keep the 1.2.0 key.
- With `notification.template.validate-before-dispatch=true`, a template failure throws `TemplateRenderException` to the caller (HTTP 422 over REST, one WARN and an offset commit on Kafka) and leaves no audit record, rate-limit token, idempotency claim or dead-letter entry; one bad request stops a `sendBatch` call.
- **New meter** `notification.template.validation.failed.total{channel,reason}` counts requests refused by `validate-before-dispatch`.

## API additions and compatibility

No type, method or constructor was removed, and every 1.2.0 constructor is kept.
Some additions can still break source code:

- **`NotificationResponse`** gains three components at the end: `failureType`, `providerMetadata` and `retryAfter`.
  The 14-argument 1.2.0 constructor is kept; a record pattern that deconstructs `NotificationResponse(...)` with 14 components no longer compiles.
- **`AcsEmailProperties`** gains the component `timeoutFailureType`; the 9-argument 1.2.0 constructor is kept, and a 9-component record pattern no longer compiles.
- **`IdempotencyRecord`** gains `fingerprint`; the 4-argument constructor is kept, and a 4-component record pattern no longer compiles.
- **`NotificationProperties.IdempotencyProperties.retryAfterFailure`** changed from `boolean` to `Boolean` (deprecated).
  `isRetryAfterFailure()` and `setRetryAfterFailure(..)` keep compiling, but Lombok now also generates `getRetryAfterFailure()` returning `Boolean`.
- **`NotificationAudit`**: the Lombok all-args constructor gained parameters; the 1.2.0 signature is kept as a deprecated constructor.

Additions:

- `NotificationResponse.failureType()`, `providerMetadata()`, `retryAfter()`, `withFailureType`, `withProviderMetadata`, `withRetryAfter`, `withErrorMessage`.
- `com.lazydevs.notification.api.idempotency.IdempotencyReleasePolicy`, a functional interface `releases(NotificationRequest, NotificationResponse)`; the default `com.lazydevs.notification.core.idempotency.FailurePolicyReleasePolicy` is registered `@ConditionalOnMissingBean`.
- `IdempotencyStore.markInProgress(key, id, fingerprint)` and `settleInProgress(key, id, response)` (default methods), `IdempotencyStore.storedForm(response, scrubber)`.
- `IdempotencyKeyReusedException` (409, `IDEMPOTENCY_KEY_REUSED`).
- `MessageScrubber` (`api.util`) with `placeholders()`.
- `NotificationService.find(...)` and `findByIdempotencyKey(...)` (default methods returning empty); `NotificationView` record.
- `NotificationAuditStore` SPI (`api.audit`); `NotificationAudit` fields `idempotencyKey`, `recipientCount`, `failureType`, `failedAt`, `updatedAt`, `applyOutcome`, `isFailure`, `toBuilder()`.
- `NotificationAuditService.recordOutcome`, `findByRequestId(tenantId, requestId)`, `findByIdempotencyKey(...)` (default methods).
- `RecipientGuard` SPI (`api.guard`) with `Decision`, `allowAll()`, `BLOCKED_ERROR_CODE`.
- `NotificationRequest.toBuilder()`.
- `com.lazydevs.notification.api.exception.TemplateRenderException extends NotificationException`, with the nested enum `Reason` (`NOT_FOUND`, `INVALID_TEMPLATE`, `MODEL_INVALID`, `EMPTY_OUTPUT`, each with `errorCode()`), `getReason()`, `getTenantId()`, `getTemplateId()`, `getVariablePath()`, the constant `HTTP_UNPROCESSABLE_ENTITY` (422), a public constructor `(Reason, tenantId, templateId, variablePath, message, cause)` and a protected one that also takes the status code.
  `TemplateNotFoundException` now extends it (reason `NOT_FOUND`); its error code `TEMPLATE_NOT_FOUND`, status 404 and constructors are unchanged.
- `NotificationTemplateEngine.renderAndValidate(NotificationRequest)`; `NotificationMetrics.recordTemplateValidationFailed(Channel, TemplateRenderException.Reason)`.
- `RateLimiter.refund(RateLimitKey)`, a `default` no-op implemented by `Bucket4jRateLimiter` and `RedisRateLimiter` (one token back, never above capacity).
- `NotificationProperties`: `TemplateProperties.legacyHelpers` and `validateBeforeDispatch`; `RateLimitRule.bandwidths`, `scope`, `effectiveBandwidths()`, `isBandwidthsExclusive()` (the `RateLimitRule(long, long, Duration)` constructor is kept); `RateLimitBandwidth`, `RateLimitScope` (`TENANT_CALLER`, `CHANNEL`), `TenantFallbackProperties`, `ProvidersProperties`, `RateLimitProperties.refundUnsent`; `IdempotencyProperties.failurePolicy`, `rejectKeyReuse`, `inProgressTimeout`, `storeScrubbedErrorMessage`, `resolvedFailurePolicy()`; `RecipientGuardProperties`.
- `com.lazydevs.notification.core.ratelimit.RateLimitRuleResolver`, shared by both limiters.
- `ProviderRegistry.PROVIDER_NOT_CONFIGURED`, `ProviderRegistry.PendingState` (`NOT_INITIALIZED`, `INIT_FAILED`) and `getUninitializedProviders()`.
- Core: `RequestFingerprint`, `IdempotencyFailurePolicy`, `StoreBackedAuditService`, `InMemoryNotificationAuditStore`, `ConfiguredRecipientGuard`, `NotificationServiceExtensions` with a new `DefaultNotificationService` constructor taking it, `StoreFeature.AUDIT`.
- notification-store-jdbc: `JdbcNotificationAuditStore`, `JdbcStoreTables.audit()`, `JdbcIdempotencyStore(…, boolean requireFingerprint)` and `detectFingerprintColumn()`.
- email-provider-acs: `AcsEmailProperties.TIMEOUT_FAILURE_TYPE` and `timeoutFailureType()`.
- REST: `TemplateRenderException` is answered with HTTP 422 and `{error, reason, templateId, path, message}`; `IdempotencyKeyReusedException` with 409 and `{notificationId, status: "KEY_REUSED", error}`.

## Known limitations

- The status view has no `retryAfter`: it reads the audit record, which does not store it.
- Redis has no audit store; with `store.type: redis` the status view needs a `NotificationAuditStore` bean or `notification.redis.audit.enabled: false`.
- The in-progress timeout compares the application clock with `recordedAt`, which the JDBC store writes with database time; clock skew shifts the timeout by the skew.
- A JDBC table without the `fingerprint` column never rejects a reused key, and rows written before the upgrade have no fingerprint, so `reject-key-reuse` cannot refuse them either.
- If the database cannot be reached at startup, the JDBC store decides about the `fingerprint` column on first use; a missing column found then logs the WARN but does not fail sends, even with `reject-key-reuse`.
- A send without an idempotency key is never refunded under `refund-unsent`.
- With `validate-before-dispatch`, refused requests leave no audit record; the WARN and the meter are the only trace.

## Upgrade notes

- **Nothing is required to keep 1.2.0 behaviour**, apart from the template error codes and include resolution above.
- **JDBC store:** add the optional fingerprint column when convenient (adjust prefix and schema); 1.2.x ignores it, so it is safe to run first:

  ```sql
  ALTER TABLE notification_idempotency ADD COLUMN IF NOT EXISTS fingerprint VARCHAR(64);
  ```

  Without it, 1.2.1 logs one WARN at startup and stores no fingerprints; with `notification.idempotency.reject-key-reuse=true` it fails startup with that statement.
- **JDBC store with `notification.audit.enabled=true`:** create `notification_audit` and its three indexes from the reference schema (`db/postgresql/notification-store.sql`, every statement `IF NOT EXISTS`) and grant the application role `SELECT, INSERT, UPDATE, DELETE` on it.
  Without audit the table is not needed.
- **Templates:** move any file your templates `<#include>` or `<#import>` under `notification.template.base-path`.
  Search your templates for `eval(`, `file(`, `js(`, `serde_` and `?new`; if none use them, set `notification.template.legacy-helpers: false` now.
  Code that parsed `INTERNAL_ERROR` to detect template problems should use the `TEMPLATE_*` error codes.
- **Before turning on `validate-before-dispatch`,** check that REST clients handle HTTP 422 and that batch callers expect one bad request to stop a `sendBatch` call.
- **Rate limits:** setting `bandwidths` together with `capacity`, `refill-tokens` or `refill-period` on the same rule fails startup with "rate-limit rule sets both 'bandwidths' and ...".
  A custom `RateLimiter` keeps working; implement `refund` only if it should give tokens back.
  A tenant with its own rate-limit override uses that override's bucket, so its sends do not count against a `scope: channel` bucket of `default-rule` or `by-channel`.
- **Replace `retry-after-failure` with `failure-policy`.**
- **ACS:** an application that must treat a timeout as "may have been delivered" sets `timeout-failure-type: ambiguous`; to have a retry under the same key dispatched again after a credential fix, declare an `IdempotencyReleasePolicy` bean.

## Configuration

**Core**, under `notification`:

| Property | Default | Meaning |
|----------|---------|---------|
| `idempotency.failure-policy` | unset (= `RELEASE_ALL`) | `RELEASE_ALL`, `KEEP` or `RELEASE_TRANSIENT`: what happens to a key after a failed send |
| `idempotency.retry-after-failure` | `true` | Deprecated alias of `failure-policy` (`true` = `RELEASE_ALL`, `false` = `KEEP`) |
| `idempotency.reject-key-reuse` | `false` | Refuse a key reused for a different request with 409 `KEY_REUSED` |
| `idempotency.in-progress-timeout` | unset | Settle a key stuck `IN_PROGRESS` longer than this as an `AMBIGUOUS` failure and replay it |
| `idempotency.store-scrubbed-error-message` | `false` | Keep the scrubbed error message in stored `FAILED`/`REJECTED` responses |
| `audit.enabled` | `false` | Audit records in the store family's audit store, behind `NotificationService.find` |
| `audit.retention-days` | `90` | Lifetime of an audit record (now enforced by the shipped stores) |
| `audit.max-entries` | `10000` | Bound of the in-memory audit store |
| `redis.audit.enabled` | unset | `false` keeps audit in memory with the Redis family |
| `recipient-guard.enabled` | `false` | Turn on the configuration-driven recipient guard |
| `recipient-guard.channels.<channel>.allowed` | empty | Allowed `@domain`, addresses or phone numbers |
| `recipient-guard.channels.<channel>.redirect-to` | unset | Replacement recipient (must be allowed); unset drops |
| `recipient-guard.channels.email.subject-prefix` | `[redirected: {originals}] ` | Prefix for redirected email; empty disables |
| `template.legacy-helpers` | `true` | Add `eval`, `file`, `js`, `serde_deserializeToMap` and `serde_serialize` and leave `?new` unrestricted, as up to 1.2; deprecated, `false` in 2.0 |
| `template.validate-before-dispatch` | `false` | Render first and refuse a template that cannot render with `TemplateRenderException`; planned default in 2.0 |
| `tenant-fallback.enabled` | `false` | A tenant with no entry under `notification.tenants` uses the providers of `notification.default-tenant` |
| `providers.lazy-init` | `false` | Build each provider on the first send that needs it instead of at startup |
| `rate-limit.<rule>.bandwidths[*].capacity` / `refill-tokens` / `refill-period` | empty list | Bands that must all allow; replaces the single-band fields of that rule |
| `rate-limit.<rule>.scope` | `tenant-caller` | `tenant-caller` or `channel` (one bucket per channel shared by every request the rule matches) |
| `rate-limit.refund-unsent` | `false` | Refund the token of a failed send whose idempotency key the release policy released, unless `AMBIGUOUS` or `UNKNOWN` |

`<rule>` is `default-rule`, `by-channel.<channel>` or `overrides[i]`.

**ACS, per tenant**, under `notification.tenants.<tenant>.channels.email.providers.acs.properties`:

| Key | Default | Description |
|-----|---------|-------------|
| `timeout-failure-type` | `transient` | `transient` or `ambiguous`: how a read timeout, connection reset, other I/O error or `TimeoutException` is classified; connect failures stay `TRANSIENT` |

**Beans you can declare** (each replaces the library's default): `IdempotencyReleasePolicy`, `RecipientGuard`, `MessageScrubber`, `NotificationAuditStore`.
