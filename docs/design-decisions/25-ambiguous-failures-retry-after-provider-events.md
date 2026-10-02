# Decision 25: Ambiguous Failures, Retry-After Cap, Provider-Originated Delivery Events

## Status: DECIDED

## Context

Three gaps showed up while designing the FCM push provider (DD-24), and none of them is specific to push.

**Timeouts after the request was sent.**
DD-13 classified every I/O failure as `TRANSIENT`, so the default predicate retried it.
That is right when the connection never opened.
It is wrong when the request went out and only the response was lost: the provider may already have accepted the message, and a retry sends it a second time.
For email and SMS a duplicate is an annoyance; for a one-time password or a payment notice it is a real problem.

**Retry-After longer than the backoff cap.**
Since 1.1.2 the retry executor honours a provider's `Retry-After` hint, but cuts it down to `max-delay`.
A provider that says "come back in 60 seconds" then gets a retry after 30, which it rejects again, wasting the attempt and the quota.

**Delivery outcomes in the send response.**
DD-16 and DD-17 only learn delivery outcomes from webhooks.
Some providers report them in the send response instead: FCM answers `UNREGISTERED` when a device token is gone, and that is the only signal the application ever gets.
Providers had no way to hand such an event to the listeners and stores that already exist.

## Decision

### `FailureType.AMBIGUOUS`

A fourth constant is appended to `FailureType`: the request may have reached the provider.

- The default `RetryPredicate` becomes `type == null || type == TRANSIENT || type == UNKNOWN`, so `AMBIGUOUS` is not retried.
- The failure takes the normal failure path: the caller gets `FAILED`, the audit records it, and the dead-letter store (when configured) keeps it with `failureType=AMBIGUOUS`, where an operator can check with the provider before replaying.
- `DefaultNotificationService` logs one WARN, "not retried: ambiguous", with the provider message id when there is one.
- `FailureTypes.fromExceptionAfterSubmit(Throwable)` gives providers a shared mapping, walking the cause chain:
  - `ConnectException`, `UnknownHostException`, `NoRouteToHostException`, `HttpConnectTimeoutException`, `SSLHandshakeException`, a `SocketTimeoutException` reading "Connect timed out", or a class named `ConnectTimeoutException` (Apache HttpClient 4 and 5) anywhere in the chain: `TRANSIENT`, because nothing was sent.
  - any other `IOException`, including `SocketTimeoutException` and `HttpTimeoutException`: `AMBIGUOUS`.
  - anything else, including `null`: `UNKNOWN`.
- `FailureTypes.fromException(Throwable)` is unchanged, so existing callers keep their behaviour.

Providers in this release:

| Provider | Becomes `AMBIGUOUS` | Stays `TRANSIENT` |
|----------|---------------------|-------------------|
| SES | `ApiCallTimeoutException`, `ApiCallAttemptTimeoutException`, an `SdkClientException` caused by a read timeout | other `SdkClientException`s, including connect failures and connect timeouts |
| SMTP | a `SocketTimeoutException` in the cause chain | connect timeouts, `MailConnectException`, other I/O failures |
| Twilio | an `ApiException` without a status caused by a read timeout | an `ApiException` without a status caused by anything else |
| ACS | unchanged | unchanged |

ACS is unchanged because, for a request with a request id, it sends an `Operation-Id` derived from the request, and a retry reuses it, so ACS can recognise the resend; its timeouts stay `TRANSIENT`.

The AWS SDK retries a read timeout itself before the SES provider sees the exception, so SES can still receive the message twice.
The SES README explains how to set the SDK's attempts to 1.

### Retry-After cap

- `RetryRule.maxRetryAfter` (`notification.retry.max-retry-after`, and per channel under `by-channel.<channel>`) bounds the hint; unset means the rule's `max-delay`.
- A hint at or below the cap: the executor waits `max(backoff, hint)`, as in 1.1.2.
- A hint above the cap: the executor stops, logs a WARN with the hint and the cap, and returns the failure, which goes to the dead-letter store.
  Holding a request thread for minutes is worse than handing the send to the replay path.
- `SendResult.withRetryAfter(Duration)` and `SendResult.failure(code, message, type, messageId, metadata)` let providers attach the hint; `FailureTypes.parseRetryAfter(header, clock)` parses delta-seconds and HTTP-dates (it replaces the private parser in the ACS provider).
- The executor's sleep goes through a package-private `Sleeper`, so tests assert the wait without sleeping.

### Provider-originated delivery events

- `DeliveryEventPublisher` (functional, `publish(DeliveryEvent)`, with `NO_OP`) and `DeliveryEventEmitter` (`setDeliveryEventPublisher(publisher)`) live in `notification-api` under `api.delivery`.
- `ProviderRegistry` takes the publisher as a third constructor argument (the two-argument constructor uses `NO_OP`).
  In `resolveAndInitialize`, which every resolution path goes through (bean name, class name, built-in name), a provider that implements `DeliveryEventEmitter` receives it before `configure()`.
- The default publisher, `ListenerDeliveryEventPublisher`, is registered with `@ConditionalOnMissingBean(DeliveryEventPublisher.class)`.
  It looks the `DeliveryEventListener` beans up on every publish, in order, so listeners created after the registry still receive events; it catches and logs a failing listener so the others run and the send is not affected; and it counts `notification.delivery-events.emitted.total{provider, status}`.
- Providers publish on the calling thread, because listeners such as the JDBC store read the tenant from `TenantContext`.
- An invalid target is published as `BOUNCED` with reason `DeliveryEvents.REASON_INVALID_TARGET` (`"INVALID_TARGET"`) and the attributes `failure`, `errorCode`, `targetType` (`token` or `fid`) and `tokenHash` (constants in `DeliveryEvents`).
  The target itself is never put in an event.

## Reasoning

### Why a new constant rather than `PERMANENT` or `UNKNOWN`

`PERMANENT` says retrying cannot help, which is false for a timeout, and it hides the real situation from operators reading the dead-letter store.
`UNKNOWN` is retried by default, which is the behaviour we want to stop.
A separate constant lets the default predicate skip it while a deployment whose providers deduplicate resends can opt back in with one line.

Appending a constant is a minor-release change because readers since 1.1.2 map unknown constants to `UNKNOWN` (`@JsonEnumDefaultValue` and the tolerant Redis and JDBC readers), so a 1.1.2 node reading a row written by 1.2.0 does not fail.
Nodes older than 1.1.2 fail on such rows, so the upgrade path is 1.1.2 first, then 1.2.0.

### Why stop instead of capping the hint

Retrying before the provider's stated time almost always fails again and costs an attempt; waiting the full time on a request thread blocks the caller.
Neither serves the caller, so a hint beyond the configured bound ends the synchronous attempt and leaves the send to the dead-letter replay path, which can run after the provider is ready.

### Why `BOUNCED` and not a new `DeliveryStatus`

`BOUNCED` already means "the recipient address is invalid or undeliverable", which is exactly what an unregistered token is.
A new constant would force every listener and dashboard to learn it, and older readers would see `UNKNOWN`.
The `reason` and attributes carry the detail for listeners that care.

### Why inject the publisher rather than have providers look up listeners

Providers are plain classes created by the `fqcn` path without Spring, so they cannot inject beans themselves.
A setter called by the registry works for all three resolution paths and keeps providers testable with a hand-made publisher.

## Consequences

### Positive

- A timed-out send is no longer silently duplicated by the default retry policy.
- A provider's Retry-After is either honoured in full or the send goes to the dead-letter store.
- Push providers can report invalid tokens through the existing listener and store seams.

### Negative

- Custom predicates written as `type != PERMANENT` retry `AMBIGUOUS` failures; the release notes call this out.
- Exhaustive `switch` statements over `FailureType` in application code must handle the new constant.
- More failures reach the dead-letter store, where an operator must decide whether to replay.

## Related Decisions

- [13-retries-and-dlq.md](./13-retries-and-dlq.md) - failure classification, the retry executor and the dead-letter store.
- [16-webhook-delivery-callbacks.md](./16-webhook-delivery-callbacks.md) - `DeliveryEvent` and `DeliveryEventListener`.
- [17-delivery-event-store.md](./17-delivery-event-store.md) - the store that receives provider-originated events.
- [23-per-channel-overrides.md](./23-per-channel-overrides.md) - per-channel retry rules, now with `max-retry-after`.
