# Decision 15: DLQ Replay

## Status: DECIDED

## Context

DD-13 shipped retries + a dead-letter store with an in-memory default
and a Redis backend (DD-14). The admin endpoint `GET /admin/dead-letter`
exposes a PII-redacted snapshot, but there is no way to *do anything*
with the entries — operators looking at a stuck email or SMS in the DLQ
have to:

1. Reconstruct the original request from logs (often impossible — the
   admin endpoint hides the payload by design).
2. Have the upstream caller re-send, with all the fragility of
   "is the bug actually fixed yet, in their service".
3. Hope the caller team owns this on-call rotation.

We promised in DD-13 §"Out of scope" that a replay endpoint would
follow:

> A future "DLQ replay" endpoint will re-submit by request id, not echo
> data. […] needs authorization, audit, and a `replay-of` reference on
> the new request.

This DD closes that loop.

## Decision

Add `POST /admin/dead-letter/{requestId}/replay`. It takes a
dead-lettered notification by its original request id, builds a *new*
notification request from the captured payload, and re-submits it
through the same `NotificationService.send()` path that the original
went through. The new request gets a fresh `requestId` and a fresh
`idempotencyKey`, with a `replayOf` field pointing back to the original
so audit logs can reconstruct the chain.

The endpoint is opt-in — enabled only when the DLQ itself is enabled
(i.e. `notification.dead-letter.enabled=true`). It lives under
`/admin/*` which is intended for operator tooling and is expected to be
firewalled / authenticated at the deployment layer (DD-08 §"Admin
surface" — the service does not ship its own auth).

### Why a new request, not a re-submission of the original

Three reasons that all point the same way:

1. **Idempotency.** The original request carries an `idempotencyKey`
   that has already passed through DD-10's two-phase claim — its
   `IdempotencyRecord` is `COMPLETE` with a `FAILED` `NotificationResponse`
   cached. Re-submitting it would short-circuit straight back to that
   cached failure.
2. **Audit.** A retried-by-replay send is a different operational
   event than the original. Stamping it with a fresh `requestId` keeps
   the audit trail honest — log search for "everything that happened to
   this customer" still works without confusing two distinct attempts.
3. **Retry budget.** The original may have exhausted retries. Replays
   should start with a fresh `attempts=0` so they get the full retry
   budget the operator expects, not a phantom remainder.

The chain is preserved by the new `replayOf` field, which is set to the
**original** request id (not the parent of an n-th replay — replays of
replays still point at the very first request, so reconstruction is
O(1)).

### Why a separate endpoint rather than a flag on the existing snapshot

`GET /admin/dead-letter` is read-only and PII-safe by construction.
Mixing in a write path with a query parameter (e.g.
`?action=replay&requestId=…`) would break the property "GET is safe to
hammer" and complicate the OpenAPI shape. A dedicated POST is the right
HTTP shape for "do this thing".

### Endpoint shape

```
POST /admin/dead-letter/{requestId}/replay
Content-Type: application/json   (no body required)
```

Successful response (`200 OK`):

```json
{
  "originalRequestId": "req-abc-123",
  "newRequestId": "req-def-456",
  "replayOf": "req-abc-123",
  "tenantId": "acme",
  "callerId": "billing-svc",
  "channel": "EMAIL",
  "status": "QUEUED",
  "message": "Replay submitted; entry removed from DLQ on successful send."
}
```

Error responses:

| Status | Cause |
|--------|-------|
| `404 Not Found` | No DLQ entry for that request id |
| `503 Service Unavailable` | DLQ is disabled (`notification.dead-letter.enabled=false`) |
| `502 Bad Gateway` | The replay reached a provider but the provider failed again — entry stays in the DLQ |
| `500 Internal Server Error` | Unexpected error replaying — entry stays in the DLQ |

### DLQ entry lifecycle

After a successful replay (i.e. the new send returns a non-`FAILED`
status), the original entry is **removed** from the DLQ. The rationale:
keeping a successfully-replayed entry around just clutters the
operator's view and makes "what's broken now?" harder to read. The
audit log still has the full record via `replayOf`.

If the replay itself ends in another DLQ entry (transient failure
again, retries exhausted), the *new* failure is added to the DLQ as a
fresh entry (with `replayOf` set on its captured request) and the
*original* is removed. This avoids two-entries-for-one-customer noise.

### SPI changes

`DeadLetterStore` gains two methods:

```java
Optional<DeadLetterEntry> findByRequestId(String tenantId, String requestId);
boolean remove(String tenantId, String requestId);
```

Both are tenant-scoped. `findByRequestId` returns the entry without
removing it (so a 502 from the replay leaves the DLQ unchanged).
`remove` returns `true` only if an entry was actually removed —
idempotent against repeated calls.

The Redis-backed implementation uses `LRANGE 0 -1` to scan the bounded
DLQ list and `LREM key 1 <serialized>` to remove a single entry.
Acceptable cost given the list is capped (default 1000) and replay is
operator-driven, not high-volume.

### Domain model changes

`NotificationRequest` gains an optional `replayOf` field — null for
fresh requests, set to the original request id for replays. The field
is recorded in `NotificationAudit` so log search can join the two
records.

REST callers cannot set `replayOf` themselves — the field is server-set
on the replay path only. If a caller submits a request with
`replayOf` populated, the value is ignored (logged at WARN, not
rejected — older clients should keep working). This matches the
DD-11 stance on `callerId` being server-set when a registry is
configured.

## Out of scope

- **Bulk replay.** Replaying every entry for a tenant is a useful
  operator action but its own design — needs a confirm-before-action
  shape, possibly a dry-run flag. Tracked.
- **Replay scheduling.** "Replay this entry in 30 minutes" is again
  separate — needs a scheduler, persistence, retry-on-the-replay-itself
  semantics.
- **Replay authorization beyond the existing admin gate.** DD-08 says
  `/admin/*` is gated at the deployment layer. Per-operator
  authorization (which engineer can replay which tenant's DLQ?) is
  someone else's problem until we hear it asked for.

## Reasoning

### Why not auto-replay on a timer

Tempting but wrong. An entry in the DLQ failed for a reason — either
retries already exhausted on a transient that hasn't recovered, or a
permanent classification (bad recipient, auth failure). Auto-replay
without operator judgment burns provider quota on already-dead sends.
The operator has signal that the system doesn't (e.g. "I just fixed
the auth config in our SES creds, the PERMANENTs are now retryable").

### Why removal-on-success rather than mark-as-replayed

Considered both. Mark-as-replayed adds a state field, a query filter,
and an "are we showing replayed entries?" toggle on the snapshot
endpoint. Removal-on-success keeps the DLQ as "the things still
broken" — minimal surface, audit log carries the historical chain.

### Why fresh idempotencyKey

The original request's idempotency key has run its course — it claimed,
attempted, recorded a `FAILED` response. Re-using it just hits the
DD-10 replay short-circuit and returns the cached failure. A fresh key
(generated server-side from the new requestId) gives the replay a
clean dedup window.

### Why 502 rather than 200-with-failure on a failed replay

Symmetry with how the original send paths work: a failed send returns a
2xx with a `FAILED` body when the failure is *expected operational
state*. A replay failure isn't expected — the operator pressed
the button because they thought the failure was fixed. 502 makes
"this didn't work, see the body for details" surface in operator
tools without them having to parse the response body.
