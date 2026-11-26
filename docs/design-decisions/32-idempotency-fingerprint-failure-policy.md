# Decision 32: Idempotency Fingerprint, Key Reuse, Failure Policy and In-Progress Timeout

## Status: DECIDED

## Context

DD-10 deduplicates on `(tenant, caller, idempotency key)` and nothing else, which leaves four gaps.

- A key reused for a different message (a client bug, or two code paths sharing a key generator) silently replays the first response, so the caller believes a message it never sent went out.
- After a failure the key is either always released (`retry-after-failure=true`, 1.1.2) or always kept (`false`).
  Neither is right for a failure that may have delivered the message: releasing invites a duplicate, keeping answers every retry with HTTP 409 until the TTL.
- A send whose process dies while it holds the key leaves an `IN_PROGRESS` record that answers HTTP 409 for the whole TTL (24 hours by default).
- 1.1.2 drops the error message of a stored failure entirely, so a failure that is replayed could not say what went wrong.

## Decision

### Request fingerprint

`RequestFingerprint.of(request)` (notification-core) is the SHA-256 hex of a canonical JSON document holding: channel, notification type, explicit template id, the recipient (every field except its tracking `id`, so every address list in order), template data, the explicitly requested provider, and per attachment its file name, size and SHA-256 (or its URL).
`NotificationRequest` has no locale field; WhatsApp's `languageCode` is part of the recipient and therefore included.

Canonical form: every string and map key NFC-normalised, map keys sorted at every depth, array order kept, numbers printed as their Java value (`45` and `45.0` differ, `45` and `45L` do not), nulls kept.
Values that are not JSON types are converted with Jackson first, so the result never depends on `toString()` or identity.
Correlation id, request id, metadata, priority and schedule are excluded; tenant and caller are already in the key.

The fingerprint is computed from the request exactly as the caller sent it, before the recipient guard (DD-34), and stored on `IdempotencyRecord.fingerprint` (new nullable component; the four-argument constructor is kept).
`IdempotencyStore.markInProgress(key, id, fingerprint)` is a new default method that drops the fingerprint, so a third-party store keeps working and simply reports `null`.
All three stores implement it: Caffeine and Redis keep it in the record, JDBC in a new nullable `fingerprint VARCHAR(64)` column; `markComplete` keeps the fingerprint of the claim.

### Key reuse

With `notification.idempotency.reject-key-reuse=true` (default `false`), a request whose key has a record with a different non-null fingerprint throws `IdempotencyKeyReusedException` (error code `IDEMPOTENCY_KEY_REUSED`), carrying the request id that first used the key.
The check runs for completed and in-progress records and after a lost claim race.
REST answers HTTP 409 with `{"notificationId": "...", "status": "KEY_REUSED", "error": "IDEMPOTENCY_KEY_REUSED"}`.
A record without a fingerprint (written by 1.2.0 or earlier, or by a store that does not keep one) is never compared.

### Failure policy

`notification.idempotency.failure-policy`:

| Policy | After a failed send | A later request under the key |
|--------|---------------------|-------------------------------|
| `RELEASE_ALL` (default) | key released | dispatched again |
| `KEEP` | key kept | HTTP 409 until the TTL |
| `RELEASE_TRANSIENT` | released only for `TRANSIENT` | after `TRANSIENT`: dispatched again; after `PERMANENT`, `AMBIGUOUS`, `UNKNOWN` or no classification: the stored `FAILED` response is replayed with `idempotentReplay=true` |

`retry-after-failure` stays as a deprecated alias (`true` is `RELEASE_ALL`, `false` is `KEEP`).
When both are set, `failure-policy` wins and a WARN is logged once when the service is built.
Under `RELEASE_TRANSIENT`, a successful release calls `refundUnsent(request)` in `DefaultNotificationService`, which gives the rate-limit token back once the registry track adds `RateLimiter.refund`; until then it only logs the bucket.

### In-progress timeout

`notification.idempotency.in-progress-timeout` (unset by default).
When a lookup finds an `IN_PROGRESS` record whose `recordedAt` is older than the timeout, it settles it: the record becomes `COMPLETE` with a `FAILED` response, error code `IN_PROGRESS_TIMEOUT`, failure type `AMBIGUOUS`, the original request id, and that response is replayed.
The audit record of the original request is updated the same way, so the status view (DD-33) shows the ambiguous failure.
A settled record is never released, under any policy, because the first attempt may have delivered.
If the slow first attempt completes later, its `markComplete` overwrites the settled outcome with the real one.

`IdempotencyStore.settleInProgress(key, notificationId, response)` is a new default method returning `false`; a store that does not implement it keeps the 1.2.0 behaviour (HTTP 409).
Each store makes it a compare-and-set on "still `IN_PROGRESS` and still owned by this notification id": Caffeine with `ConcurrentMap.replace(key, old, new)`, Redis with a Lua script, JDBC with one conditional `UPDATE`.
A lookup that loses the settle race answers from whatever the record is now.

### Scrubbed messages

`MessageScrubber` (notification-api, functional) scrubs every error message the library persists: the idempotency record's stored response and the audit record (and therefore the status view).
The default, `MessageScrubber.placeholders()`, replaces email addresses with `[email]` and E.164-looking numbers with `[phone]`, using the patterns of `PiiMasking.redact`.
`IdempotencyStore.storedForm(response)` now keeps the message after the default scrubber instead of dropping it; `storedForm(response, scrubber)` takes another one.
An application `MessageScrubber` bean replaces the default; one that returns `null` restores the 1.2.0 behaviour of storing no message.
The dead-letter store is unchanged: it keeps the full request for replay, as before.

## Reasoning

### Why hash what is sent rather than the whole request

Two requests that differ only in correlation id or metadata send the same message; refusing the second would break callers that generate a fresh correlation id per retry.
Everything that changes who receives what is in the fingerprint.

### Why a null fingerprint is never compared

Rows from 1.2.0 have none, and treating "unknown" as "different" would turn an upgrade into a wave of 409s.

### Why replay instead of 409 for kept failures under RELEASE_TRANSIENT

The caller asked "did this send happen?"; the stored failure is the answer, and the replay flag tells it that it is seeing the first attempt.
`KEEP` keeps its 1.1.x meaning for deployments that rely on the 409.

### Why the timeout settles as AMBIGUOUS and never releases

The service cannot know whether the stuck attempt reached the provider.
Releasing would trade a 24-hour 409 for a possible duplicate; settling as `AMBIGUOUS` gives the caller a definite answer and leaves reconciliation to an operator.

### Why a compare-and-set per store

Two lookups can find the same stale record at the same moment, and the first attempt can complete at that moment too.
Only a compare-and-set in the store keeps exactly one outcome.

## Consequences

### Positive

- Key misuse becomes visible instead of silently replaying the wrong response.
- Possibly delivered failures are no longer re-sent under `RELEASE_TRANSIENT`.
- A dead process no longer blocks its key for the whole TTL.
- Stored failures keep a useful, scrubbed message.

### Negative

- JDBC hosts must add the `fingerprint` column before deploying 1.3.0 (one additive `ALTER TABLE`, in the module README).
- The timeout compares the application clock with `recordedAt`, which the JDBC store writes with database time; clock skew shifts the timeout by the skew.
- Stored failures now hold a scrubbed message where 1.2.0 held none; text the scrubber does not recognise (for example a name) is kept.
- Lookups by key in the in-memory and Redis stores read the whole record to check the fingerprint, as they already did for the status.

## Related Decisions

- [10-idempotency.md](./10-idempotency.md) - the idempotency contract this extends
- [12-rate-limiting.md](./12-rate-limiting.md) - token handling around the claim
- [31-failure-type-on-notification-response.md](./31-failure-type-on-notification-response.md) - the failure type the policy reads
- [33-status-view.md](./33-status-view.md) - the settled outcome in the status view
- [34-recipient-guard.md](./34-recipient-guard.md) - the fingerprint uses the request before the guard
