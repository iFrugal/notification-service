# Decision 31: Failure Type and Provider Metadata on NotificationResponse

## Status: DECIDED

## Context

`SendResult` has carried a `FailureType` since DD-13 and provider metadata since 1.0, but `DefaultNotificationService` dropped both when it built the `NotificationResponse`.
A caller that got `FAILED` could not tell a throttled send (safe to retry) from a rejected address (pointless) or a timeout after the provider may have accepted the message (a retry may duplicate it).
The same information was missing from the idempotency record, so a replayed failure (DD-32) could not say what kind of failure it was.

The ACS classifier also disagreed with the rest of the library.
It reported every timeout and I/O error as `TRANSIENT`, although DD-25 introduced `AMBIGUOUS` for exactly the "request may have reached the provider" case, and it reported 401/403 as `PERMANENT`, although a credential problem never reaches a mailbox and is fixed by configuration.

## Decision

### Two new components

`NotificationResponse` gains `FailureType failureType` and `Map<String, Object> providerMetadata`, appended after `idempotentReplay`.
Both are annotated `@JsonInclude(NON_NULL)`, so a successful response serialises exactly as in 1.2.0.
The 1.2.0 fourteen-argument constructor is kept and leaves both `null`; the canonical constructor is marked `@JsonCreator` so Jackson keeps using it.
`withFailureType`, `withProviderMetadata` and `withErrorMessage` return modified copies; the record copies `providerMetadata` into an unmodifiable map, and an empty map becomes `null`.

### Who fills them

- A provider failure: the result's `failureType` (`UNKNOWN` when the provider gave none) and its `providerMetadata`.
- A provider success: its `providerMetadata`; `failureType` stays `null`.
- A `NotificationException` before or during dispatch (template, provider resolution, recipient guard): `PERMANENT`, the same value the dead-letter entry already used.
- An unexpected exception: `UNKNOWN`.

`IdempotencyStore.storedForm` and `NotificationResponse.replayedFrom` keep both fields, so a replay carries the original classification.
Readers with `READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE` (the Redis and JDBC stores) read a future constant as `UNKNOWN`.

### ACS classification

| Error | 1.2.0 | 1.3.0 |
|-------|-------|-------|
| HTTP 401 / 403, `ClientAuthenticationException` or `CredentialUnavailableException` in the cause chain | `PERMANENT` | `TRANSIENT` |
| HTTP 408, 429, 5xx | `TRANSIENT` | `TRANSIENT` |
| Other HTTP 4xx | `PERMANENT` | `PERMANENT` |
| Connect failure (refused, unknown host, connect timeout, TLS handshake) | `TRANSIENT` | `TRANSIENT` |
| Read timeout, connection reset, other `IOException`, `TimeoutException` | `TRANSIENT` | `AMBIGUOUS` |
| Anything else | `UNKNOWN` | `UNKNOWN` |

The connect and I/O split reuses `FailureTypes.fromExceptionAfterSubmit` from DD-25.

## Reasoning

### Why components on the record rather than a wrapper

The response is the one object every caller already receives, over Java, REST and Kafka.
A wrapper or a side channel would force every caller to change; two nullable components omitted from JSON change nothing for callers that ignore them.

### Why ACS timeouts become AMBIGUOUS despite the Operation-Id

DD-25 kept ACS timeouts `TRANSIENT` because a resend carries the same `Operation-Id` and ACS can recognise it.
That holds for the retry executor, but the classification now also drives the idempotency failure policy (DD-32): under `RELEASE_TRANSIENT` a `TRANSIENT` failure releases the key, and the caller may then send again under a new request id, which gets a new operation id.
Calling a lost answer `AMBIGUOUS` is the honest description, and a deployment that trusts the operation id can still retry it with its own `RetryPredicate`.

### Why credential failures are TRANSIENT

A 401 or 403 from ACS means the connection string or the identity's role is wrong; nothing was delivered and the same request succeeds once configuration is fixed.
`PERMANENT` would keep the key under `RELEASE_TRANSIENT` and replay the failure after the fix.

## Consequences

### Positive

- Callers and operators see why a send failed without reading logs or the dead-letter store.
- Replayed failures keep their classification.
- ACS now follows the same timeout rules as SES, SMTP and Twilio.

### Negative

- The record's canonical constructor has two more parameters; code calling it with sixteen arguments must be written against 1.3.0 (the 1.2.0 constructor still works).
- ACS read timeouts are no longer retried by the default predicate, so a deployment that relied on that retry must add a predicate.
- 401/403 are now retried by the retry executor, which spends `max-attempts` on a misconfiguration before it fails.

## Related Decisions

- [13-retries-and-dlq.md](./13-retries-and-dlq.md) - `FailureType` and the retry predicate
- [25-ambiguous-failures-retry-after-provider-events.md](./25-ambiguous-failures-retry-after-provider-events.md) - `AMBIGUOUS` and `fromExceptionAfterSubmit`; this decision reverses its ACS row
- [32-idempotency-fingerprint-failure-policy.md](./32-idempotency-fingerprint-failure-policy.md) - uses `failureType` for the failure policy
