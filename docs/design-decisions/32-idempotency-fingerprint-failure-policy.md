# Decision 32: Idempotency Fingerprint, Key Reuse, Failure Policy, Release Policy and In-Progress Timeout

## Status: DECIDED

## Context

DD-10 deduplicates on `(tenant, caller, idempotency key)` and nothing else, which leaves four gaps.

- A key reused for a different message (a client bug, or two code paths sharing a key generator) silently replays the first response, so the caller believes a message it never sent went out.
- After a failure the key is either always released (`retry-after-failure=true`, 1.1.2) or always kept (`false`).
  Neither is right for a failure that may have delivered the message: releasing invites a duplicate, keeping answers every retry with HTTP 409 until the TTL.
- A send whose process dies while it holds the key leaves an `IN_PROGRESS` record that answers HTTP 409 for the whole TTL (24 hours by default).
- 1.1.2 drops the error message of a stored failure entirely, so a failure that is replayed cannot say what went wrong.
- Whether a failure may release its key is a judgement some applications must make themselves, for example for a provider error code that means the sender's credential was rejected.

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

The JDBC column is optional.
`JdbcIdempotencyStore` probes for it once (`SELECT fingerprint FROM <table> WHERE 1 = 0`, with the configured schema and table prefix) when the auto-configuration builds it, and again on first use if the probe could not decide (database unreachable, table not created yet).
When the column is missing it logs one WARN with the `ALTER TABLE` statement and runs without fingerprints, so a 1.2.0 table keeps working after the jar is upgraded.
With `reject-key-reuse=true` a missing column fails startup with the same statement instead, because the check could never fire.
The audit table (DD-33) is only needed when `notification.audit.enabled=true`.

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

### Release policy SPI

The release decision is the public SPI `com.lazydevs.notification.api.idempotency.IdempotencyReleasePolicy`, a functional interface `releases(NotificationRequest request, NotificationResponse failed)`.
The default bean, `FailurePolicyReleasePolicy` (notification-core), implements the table above from `failure-policy`, read on every call; it is registered `@ConditionalOnMissingBean`, so an application bean replaces it.
The service asks the policy right after a failed send, and again when a later request finds a failed record still stored (written by an older version, or left by a release that failed).
A failure the policy keeps is replayed under `RELEASE_TRANSIENT` and answered with HTTP 409 otherwise, as before.
A record settled by the in-progress timeout is never offered to the policy and is never released.
A stored `COMPLETE` record without a response, which only a broken store writes, is still judged by `failure-policy` alone.

### Rate-limit refund

With `notification.rate-limit.refund-unsent=true` (DD-30, default `false`), a key the release policy released gets its rate-limit token back from the bucket the send consumed (`RateLimiter.refund` with the same `RateLimitKey`), so the caller's retry is not charged twice.
A failure classified `AMBIGUOUS` or `UNKNOWN`, or not classified, is never refunded, even when a policy releases it, because the provider may have accepted it.
With the default policy this means: `RELEASE_TRANSIENT` refunds `TRANSIENT` failures, `RELEASE_ALL` refunds `TRANSIENT` and `PERMANENT` ones, `KEEP` refunds nothing.
A refund that throws is logged and ignored.

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

`MessageScrubber` (notification-api, functional) scrubs every error message the library persists: the audit record (and therefore the status view) and, when opted in, the idempotency record's stored response.
The default, `MessageScrubber.placeholders()`, replaces email addresses with `[email]` and E.164-looking numbers with `[phone]`, using the patterns of `PiiMasking.redact`.
An application `MessageScrubber` bean replaces the default.

The stored copy of a `FAILED` or `REJECTED` response still has no error message by default, as in 1.1.2 to 1.2.0.
`notification.idempotency.store-scrubbed-error-message=true` (default `false`) keeps the scrubbed message instead, so a replayed failure can say what went wrong.
`IdempotencyStore.storedForm(response)`, which the stores call as a safety net, scrubs with the default scrubber; `storedForm(response, scrubber)` takes another one, and a scrubber that returns `null` drops the message.
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

### Why the release decision is an SPI

The enum covers the common cases, but only the application knows whether a provider's error code means "nothing reached the recipient".
A credential rejected by the provider is the usual example: the classification stays `PERMANENT` so the retry executor does not retry a misconfiguration, while the application can still let a retry under the same key be dispatched once the credential is fixed.

### Why the stored error message stays opt-in

1.1.2 removed the message from stored failures on purpose, because provider text can quote recipient data the scrubber does not recognise, such as a name.
Keeping it is useful for replays but is a change in what the store holds, so an application decides.

### Why a missing JDBC column does not fail startup

1.2.0 hosts apply DDL with their own migration tool; requiring the column before the jar can start would turn a jar upgrade into a coordinated schema change.
Running without fingerprints only disables the key-reuse check, so only that setting makes the column mandatory.

### Why a compare-and-set per store

Two lookups can find the same stale record at the same moment, and the first attempt can complete at that moment too.
Only a compare-and-set in the store keeps exactly one outcome.

## Consequences

### Positive

- Key misuse becomes visible instead of silently replaying the wrong response.
- Possibly delivered failures are no longer re-sent under `RELEASE_TRANSIENT`.
- A dead process no longer blocks its key for the whole TTL.
- Stored failures can keep a useful, scrubbed message.
- An application can widen or narrow the release decision without forking the coordinator.

### Negative

- JDBC hosts that want fingerprints add the `fingerprint` column (one additive `ALTER TABLE`, in the module README); until then the store logs a WARN at startup.
- The timeout compares the application clock with `recordedAt`, which the JDBC store writes with database time; clock skew shifts the timeout by the skew.
- With `store-scrubbed-error-message`, stored failures hold a scrubbed message where 1.2.0 held none; text the scrubber does not recognise (for example a name) is kept.
- A custom release policy that releases possibly delivered failures can cause duplicates; the refund still never returns their token.
- Lookups by key in the in-memory and Redis stores read the whole record to check the fingerprint, as they already did for the status.

## Related Decisions

- [10-idempotency.md](./10-idempotency.md) - the idempotency contract this extends
- [12-rate-limiting.md](./12-rate-limiting.md) - token handling around the claim
- [30-rate-limit-bandwidths-scope-refund.md](./30-rate-limit-bandwidths-scope-refund.md) - `RateLimiter.refund` and `refund-unsent`
- [31-failure-type-on-notification-response.md](./31-failure-type-on-notification-response.md) - the failure type the policy reads
- [33-status-view.md](./33-status-view.md) - the settled outcome in the status view
- [34-recipient-guard.md](./34-recipient-guard.md) - the fingerprint uses the request before the guard
