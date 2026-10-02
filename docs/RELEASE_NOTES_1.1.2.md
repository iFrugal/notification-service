# notification-service 1.1.2

## Highlights

- **A retry after a failed send dispatches again.**
  A request that reuses the idempotency key of a `FAILED` or `REJECTED` attempt is sent again instead of being answered with HTTP 409 until the key's TTL elapses.
- **No provider error text in responses or stored records.**
  Error messages are redacted on every failure path of the core service, and the idempotency stores no longer persist the error text of failed responses.
- **Stores tolerate data from newer versions.**
  The Redis and JDBC stores read records that carry fields or enum constants this version does not know.
  Upgrade to 1.1.2 before 1.2.0 so stores tolerate the new constants.

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

## Templates

<!-- Template engine changes for 1.1.2 are added by the template branch. -->

## Compatibility

Fully backward compatible with 1.1.1: no public signature was removed or changed, and no record constructor changed.
Additive public API:

- `IdempotencyStore.release(IdempotencyKey, String notificationId)`, a default method that returns `false`, so existing store implementations compile and keep the 1.1.x behaviour.
- `IdempotencyStore.storedForm(NotificationResponse)`, a static helper for store implementations.
- `@JsonEnumDefaultValue` on `FailureType.UNKNOWN` and `DeliveryStatus.UNKNOWN`.
- The property `notification.idempotency.retry-after-failure` (default `true`).

Behaviour changes to be aware of:

- A retry under the key of a failed attempt is dispatched instead of rejected with HTTP 409.
- `errorMessage` of a failed response is redacted on every path, and an idempotency record of a failed response has no `errorMessage`.

Rolling upgrade from 1.1.0 or 1.1.1:

- Records are written in the same format, so older and newer nodes can share one Redis or one database.
- An older node still answers a retry after a failure with HTTP 409 until it is upgraded.
- Upgrade to 1.1.2 before 1.2.0 so stores tolerate the new constants.
