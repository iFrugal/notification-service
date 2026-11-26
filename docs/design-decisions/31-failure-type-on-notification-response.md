# Decision 31: Failure Type, Provider Metadata and Retry Hint on NotificationResponse

## Status: DECIDED

## Context

`SendResult` has carried a `FailureType` since DD-13, provider metadata since 1.0 and a retry hint since 1.1.2, but `DefaultNotificationService` dropped all three when it built the `NotificationResponse`.
A caller that got `FAILED` could not tell a throttled send (safe to retry, and after how long) from a rejected address (pointless) or a timeout after the provider may have accepted the message (a retry may duplicate it).
The same information was missing from the idempotency record, so a replayed failure (DD-32) could not say what kind of failure it was.

The ACS classifier reports every timeout and I/O error as `TRANSIENT`.
DD-25 decided that on purpose: a resend carries the same `Operation-Id`, so ACS recognises it.
Some applications still need to treat a lost answer as "may have been delivered", for example when the idempotency failure policy (DD-32) would otherwise release the key and the caller then sends again under a new request id.

## Decision

### Three new components

`NotificationResponse` gains `FailureType failureType`, `Map<String, Object> providerMetadata` and `Duration retryAfter`, appended after `idempotentReplay`.
All three are annotated `@JsonInclude(NON_NULL)`, so a successful response serialises exactly as in 1.2.0.
`retryAfter` is written as an ISO-8601 duration (`"PT30S"`) whatever the mapper's duration settings, so REST, Redis and JDBC agree; a zero or negative value is no hint and becomes `null`.
The 1.2.0 fourteen-argument constructor is kept and leaves all three `null`; the canonical constructor is marked `@JsonCreator` so Jackson keeps using it.
`withFailureType`, `withProviderMetadata`, `withRetryAfter` and `withErrorMessage` return modified copies; the record copies `providerMetadata` into an unmodifiable map, and an empty map becomes `null`.

### Who fills them

- A provider failure: the result's `failureType` (`UNKNOWN` when the provider gave none), its `providerMetadata`, and `SendResult.retryAfter()` (for example an HTTP 429 `Retry-After`).
- A provider success: its `providerMetadata`; `failureType` and `retryAfter` stay `null`.
- A `NotificationException` before or during dispatch (template, provider resolution, recipient guard): `PERMANENT`, the same value the dead-letter entry already used.
- An unexpected exception: `UNKNOWN`.

`IdempotencyStore.storedForm` and `NotificationResponse.replayedFrom` keep all three fields, so a replay carries the original classification and hint.
Readers with `READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE` (the Redis and JDBC stores) read a future constant as `UNKNOWN`, and the 1.2.0 readers ignore the new fields.
The status view (DD-33) does not carry `retryAfter`: it reads the audit record, which has no column for it.

### ACS classification: DD-25 stays, AMBIGUOUS is an opt-in

The default classification is unchanged from 1.2.0.
The ACS provider property `timeout-failure-type` (`transient`, the default, or `ambiguous`) lets an application opt in to `AMBIGUOUS`:

| Error | Default (`transient`) | `timeout-failure-type: ambiguous` |
|-------|-----------------------|-----------------------------------|
| HTTP 408, 429, 5xx | `TRANSIENT` | `TRANSIENT` |
| HTTP 401, 403 and other 4xx | `PERMANENT` | `PERMANENT` |
| Connect failure (refused, unknown host, connect timeout, TLS handshake) | `TRANSIENT` | `TRANSIENT` |
| Read timeout, connection reset, other `IOException`, `TimeoutException` | `TRANSIENT` | `AMBIGUOUS` |
| Anything else | `UNKNOWN` | `UNKNOWN` |

The connect and I/O split reuses `FailureTypes.fromExceptionAfterSubmit` from DD-25.
`AcsEmailProperties` gains the component `timeoutFailureType`; its 1.2.0 nine-argument constructor is kept and means `TRANSIENT`.

## Reasoning

### Why components on the record rather than a wrapper

The response is the one object every caller already receives, over Java, REST and Kafka.
A wrapper or a side channel would force every caller to change; nullable components omitted from JSON change nothing for callers that ignore them.

### Why ACS timeouts stay TRANSIENT by default

A resend under the same request id carries the same `Operation-Id`, and ACS de-duplicates on it, so retrying a lost answer cannot deliver twice.
Changing the default would have stopped the retry executor from retrying ACS timeouts for every user, a behaviour change that DD-25 had explicitly decided against.
An application whose callers may resend under a new request id, and so a new operation id, opts in to `AMBIGUOUS`; under `failure-policy=RELEASE_TRANSIENT` its idempotency key is then kept and the failure replayed.

### Why 401 and 403 stay PERMANENT

A credential failure does not fix itself, so retrying it in the retry executor spends `max-attempts` on a misconfiguration.
An application that wants a retry under the same idempotency key to be dispatched again once the credential is fixed declares an `IdempotencyReleasePolicy` (DD-32) that releases those failures, without changing the classification for anyone else.

### Why retryAfter is a Duration

`SendResult.retryAfter()` is already a `Duration` relative to the failed attempt (DD-25), and that is what a caller needs to schedule its retry.
An `Instant` would depend on the clocks of the provider, the library and the caller agreeing.

## Consequences

### Positive

- Callers and operators see why a send failed, and how long the provider asked them to wait, without reading logs or the dead-letter store.
- Replayed failures keep their classification and hint.
- ACS users keep the DD-25 behaviour; those who need the stricter reading turn it on per tenant.

### Negative

- The record's canonical constructor has three more parameters; code calling it with all components must be written against 1.2.1 (the 1.2.0 constructor still works).
- `AcsEmailProperties` has one more component; a record pattern that deconstructs it with nine components no longer compiles.
- The status view has no `retryAfter`.

## Related Decisions

- [13-retries-and-dlq.md](./13-retries-and-dlq.md) - `FailureType` and the retry predicate
- [25-ambiguous-failures-retry-after-provider-events.md](./25-ambiguous-failures-retry-after-provider-events.md) - `AMBIGUOUS`, `fromExceptionAfterSubmit` and `SendResult.retryAfter`; its ACS row stays decided
- [32-idempotency-fingerprint-failure-policy.md](./32-idempotency-fingerprint-failure-policy.md) - uses `failureType` for the failure policy and the release policy
