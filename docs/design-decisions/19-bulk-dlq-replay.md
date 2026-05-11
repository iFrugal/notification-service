# Decision 19: Bulk DLQ Replay

## Status: DECIDED

## Context

DD-15 added single-entry DLQ replay
(`POST /admin/dead-letter/{requestId}/replay`). It's the right shape
for "the user called support, here's the request id, replay this one
specifically." It is *not* the right shape for the operationally
common situation:

- A provider had a 45-minute outage. The DLQ has 8,000 entries from
  that window, all transient.
- Configuration was wrong (auth token rotated, SES sending paused). A
  thousand permanent-classification entries are now actually
  retryable.

In both cases the operator wants a one-shot "replay everything that
matches this scope." Today they'd write a loop against the single-entry
endpoint, which is slow, fragile (what about ordering? what about a
half-failed batch?) and an obvious gap in the operator surface.

## Decision

Add `POST /admin/dead-letter/replay-batch` with three behaviour modes:

```
POST /admin/dead-letter/replay-batch?tenantId=acme&dryRun=true&limit=100
POST /admin/dead-letter/replay-batch?tenantId=acme&limit=100
POST /admin/dead-letter/replay-batch?tenantId=acme           # default limit
```

The endpoint walks `DeadLetterStore.snapshot()` filtered by
`tenantId`, takes the first `limit` entries (most-recent-first), and:

- **Dry-run** (`?dryRun=true`): returns the preview list (request ids,
  channels, original failure type) without calling
  `NotificationService.send()` or `DeadLetterStore.remove()`.
- **Live** (default): for each entry, calls
  `NotificationService.send()` with a derived replay request
  (same logic DD-15 uses), tallies results, removes successful
  entries from the DLQ, and returns a per-entry result list.

### Why a separate endpoint rather than overloading single-replay

Considered `POST /admin/dead-letter/replay?tenantId=acme&all=true`
on the existing single-entry endpoint. Rejected — single-entry is
"replay this resource" (path-scoped). Batch is "perform an action on
many resources" (collection-scoped). REST shape gets cleaner with
two endpoints than with one mode-switched endpoint.

### Why mandatory `?tenantId`

Bulk operations across tenants are dangerous in a way single-entry
isn't. An operator typo on tenant id with `?tenantId=acme` blast-radius
is one tenant; the same typo without the param blast-radius is the
entire DLQ. The DD-15 single-replay endpoint accepts `?tenantId`
optionally (defaults to `default-tenant`); for bulk it's required.

### Why `dryRun` defaults to off (live)

Considered defaulting `dryRun=true` and requiring `?dryRun=false` to
actually execute. Decided against — the live form is the actual
operational tool; defaulting to "type the param twice to do anything"
adds friction without protection. Operators who want a preview ask
for `?dryRun=true` explicitly, and the response shape includes a
prominent `mode: "live"` field so the response itself confirms what
happened.

### Limit semantics

Defaults to 100; capped at 1,000 via `Math.clamp`. Replaying 8,000
entries in one HTTP call is a load problem we don't want by default —
it ties up a connection for minutes and risks client-side timeouts. A
caller wanting to drain 8,000 entries calls the endpoint 80 times,
which is fine.

### Response shape

```json
{
  "mode": "live",
  "tenantId": "acme",
  "requested": 100,
  "replayed": 87,
  "stillDeadLettered": 13,
  "skippedDlqDisabled": 0,
  "entries": [
    {
      "originalRequestId": "req-001",
      "newRequestId": "req-9a8b...",
      "status": "SENT",
      "removedFromDlq": true
    },
    {
      "originalRequestId": "req-002",
      "status": "FAILED",
      "errorCode": "PROVIDER_TIMEOUT",
      "errorMessage": "smtp 421",
      "removedFromDlq": false
    }
  ]
}
```

For dry-run, `entries` carries the preview shape (no `newRequestId`,
no `status`) and `mode: "dry-run"` distinguishes the response.

### Status codes

- `200` — completed (live or dry-run). Per-entry failures within the
  batch don't change the response code; they appear in the `entries`
  array. The batch *itself* succeeded — operators see which
  individual replays failed via the body.
- `503` — DLQ disabled (matches `/admin/dead-letter` convention).
- `400` — `tenantId` missing or blank.

A failed *individual* replay does not surface as 502 the way DD-15's
single-replay does. A 502 on the batch would discard the per-entry
detail, which is exactly what an operator running a recovery action
needs to see.

### Idempotency

Each per-entry replay carries a fresh `replay-<uuid>` idempotency
key (same as DD-15). Re-running the same bulk replay after a partial
success only re-touches entries still in the DLQ — successful ones
are removed. Safe to retry the batch.

## Out of scope

- **Predicate-based filter beyond `tenantId` + `limit`.** Filtering
  by `failureType`, `channel`, or date range would be useful but
  expands the endpoint surface significantly. Operators with
  channel-specific recovery needs filter client-side from
  `/admin/dead-letter` and replay by id.
- **Scheduled / queued bulk replay.** "Replay these 8000 entries
  over the next hour at 10/sec" is rate-limit-aware backpressure,
  not bulk replay. Out of scope.
- **Cross-tenant bulk replay.** Explicitly rejected — see "Why
  mandatory tenantId".
- **Async batch.** The endpoint is synchronous. A 1000-entry batch
  takes seconds to tens of seconds depending on provider; not enough
  pressure to justify an async-callback shape.

## Reasoning

### Why per-entry results in the response rather than batched status

An operator running this is fixing a real problem. They want
diagnostic detail: "of those 100, which 13 still failed and why?"
A batched "97 sent, 13 failed" response means a second tool to
investigate individual failures. The per-entry list is the
diagnostic.

### Why successful entries are removed and failed entries kept

Same logic as DD-15: the DLQ is "things still broken." A replay that
succeeded isn't broken; a replay that failed still is. The next bulk
replay only revisits the still-failing entries.

### Why no rate-limit awareness

`NotificationService.send()` already runs through DD-12 rate-limiting.
A bulk replay that exceeds the bucket gets `RateLimitExceededException`
on individual entries — which the endpoint catches and surfaces as
`status: "FAILED"` in that entry's row. Operators see the rate-limit
hit and either raise the bucket or replay the batch in smaller chunks.
