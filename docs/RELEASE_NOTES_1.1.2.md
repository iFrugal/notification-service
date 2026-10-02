# notification-service 1.1.2

## Highlights

- **A retry after a failed send dispatches again.**
  A request that reuses the idempotency key of a `FAILED` or `REJECTED` attempt is sent again instead of being answered with HTTP 409 until the key's TTL elapses.
- **No provider error text in responses or stored records.**
  Error messages are redacted on every failure path of the core service, and the idempotency stores no longer persist the error text of failed responses.
- **Stores tolerate data from newer versions.**
  The Redis and JDBC stores read records that carry fields or enum constants this version does not know.
  Upgrade to 1.1.2 before 1.2.0 so stores tolerate the new constants.

- **Health indicators can be switched off one by one.**
  `management.health.dlq.enabled`, `management.health.delivery-events.enabled`, `management.health.idempotency.enabled` and `management.health.rate-limit.enabled` now work, as does `management.health.defaults.enabled`.

- **The retry executor honours a provider's Retry-After.**
  A provider can pass a delay hint in `SendResult.providerMetadata`; the ACS provider does so for throttling and other transient HTTP errors.

## Fixes

- **Retry under the same idempotency key after a failure (DD-10).**
  DD-10 promises that a `FAILED` or `REJECTED` outcome is not replayed and a retry is treated as fresh, but the failed attempt left a record behind, so `markInProgress` refused the retry and the caller got HTTP 409 until the TTL elapsed.
  The service now releases the record after a failed outcome, and a retry that finds a failed record written by 1.1.0 or 1.1.1 releases it before claiming the key.
  The release is a compare-and-delete on the failed attempt's notification id; `markInProgress` stays the only atomic gate, so of several concurrent retries exactly one dispatches.
  Set `notification.idempotency.retry-after-failure=false` to keep the 1.1.x behaviour.
- **Provider error text in responses.**
  The error message of a failed response came verbatim from custom providers, from a `NotificationException` and from unexpected exceptions, and `RetryExecutor` copied the message of a thrown exception into its `SendResult`.
  All of them now pass through `PiiMasking.redact`, so the response, the audit record and the dead-letter entry carry `j***@example.com` instead of the address.
- **Provider error text in idempotency records.**
  The Caffeine, Redis and JDBC idempotency stores persisted the full failed response, including its error message.
  A failed or rejected response is never replayed, so the stores now persist it without the error message.
- **Unknown fields and enum constants in stored data.**
  The Redis stores failed to read a record with a field they did not know, so a record written by a newer version read as absent.
  The Redis and JDBC stores now ignore unknown fields and read an unknown `FailureType` or `DeliveryStatus` constant as `UNKNOWN`.

- **Retry-After from ACS was ignored.**
  ACS answers throttling with HTTP 429 and a `Retry-After` header, but the `RetryExecutor` retried on its own backoff, which could be shorter and run into the throttle again.
  The ACS provider now copies the header, as seconds or an HTTP-date, into `SendResult.providerMetadata` under `SendResult.RETRY_AFTER_METADATA_KEY`, and the executor waits at least that long, capped at `notification.retry.max-delay`.

## Templates

- **The shipped default templates load again.**
  The engine resolved `classpath:/templates/templates/...` because it prefixed the base path to a path that already began with `templates/`, so the standalone server's default templates were never found.
  Paths are now `<base-path><tenant>/<channel>/<id>.ftl`; the old doubled layout still resolves for one release with a warning naming the file.
- **Interpolated data can no longer hijack a message.**
  `[SUBJECT]` and `[BODY]` markers are replaced by per-render random tokens in the template source before rendering, so a recipient name containing `[BODY]...[/BODY]` is plain text instead of replacing the body.
- **Tenant and template ids are validated before any resource path is built.**
  Ids must match `[A-Za-z0-9][A-Za-z0-9._-]{0,127}` and may not contain `..`; anything else is a 404 whose message does not echo the value.
  Deployments whose tenant or template ids used spaces, `@`, `:`, non-ASCII characters or a leading `.`, `_` or `-` must rename them.
- Null values render as empty strings in `escapeHtml`, `truncate`, `capitalize` and `urlEncode`, and `truncate` no longer throws for lengths under three.
- `formatDate` and `formatDateTime` accept every `java.time` type, `java.util.Date`, epoch millis and ISO-8601 strings; unparseable input is printed unchanged.
  Optional trailing zone and locale arguments were added; defaults are unchanged.
- The template cache honours `notification.template.cache-ttl-seconds` (default one hour; it was bound but never read) and the new `notification.template.cache-max-size` (default 1000).
- Opt-in HTML auto-escaping: `notification.template.auto-escape=true` escapes interpolated values in HTML bodies; `escapeHtml()` returns markup so existing calls are not escaped twice, and `?no_esc` passes trusted fragments.
  The default is `false`, so output is unchanged unless you opt in.
- An optional `[TEXT]...[/TEXT]` section gives an HTML template a plain-text part (multipart); HTML detection now also recognises `<table`, `<br`, `<span`, `<a ` and `<!DOCTYPE`.
- Known limitation: `<#include>` does not resolve relative to the base path, and the shared FreeMarker configuration from persistence-utils injects `file`, `eval` and `js` helpers into every model.
  Both are addressed when the engine owns its FreeMarker configuration in 1.2.

## Compatibility

Fully backward compatible with 1.1.1: no public signature was removed or changed, and no record constructor changed.
Additive public API:

- `IdempotencyStore.release(IdempotencyKey, String notificationId)`, a default method that returns `false`, so existing store implementations compile and keep the 1.1.x behaviour.
- `IdempotencyStore.storedForm(NotificationResponse)`, a static helper for store implementations.
- `@JsonEnumDefaultValue` on `FailureType.UNKNOWN` and `DeliveryStatus.UNKNOWN`.
- The property `notification.idempotency.retry-after-failure` (default `true`).
- `SendResult.RETRY_AFTER_METADATA_KEY` and `SendResult.retryAfter()`.
- The properties `notification.template.auto-escape` (default `false`) and `notification.template.cache-max-size` (default `1000`); `notification.template.cache-ttl-seconds` is now honoured.

- The `management.health.<name>.enabled` switches for the four notification health indicators; they default to `management.health.defaults.enabled`, which is `true` unless you set it.

Behaviour changes to be aware of:

- A retry under the key of a failed attempt is dispatched instead of rejected with HTTP 409.
- `errorMessage` of a failed response is redacted on every path, and an idempotency record of a failed response has no `errorMessage`.
- A retry after a provider error with a `Retry-After` hint can wait longer than before, up to `notification.retry.max-delay`.
- If you set `management.health.defaults.enabled=false`, the notification indicators are now switched off with the rest; enable the ones you want explicitly.
- Templates are re-read after the cache TTL (default one hour) instead of being cached forever, and unsafe tenant or template ids are rejected with 404.

Rolling upgrade from 1.1.0 or 1.1.1:

- Records are written in the same format, so older and newer nodes can share one Redis or one database.
- An older node still answers a retry after a failure with HTTP 409 until it is upgraded.
- Upgrade to 1.1.2 before 1.2.0 so stores tolerate the new constants.
