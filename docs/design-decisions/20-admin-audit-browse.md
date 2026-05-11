# Decision 20: Admin Audit Browse

## Status: DECIDED

## Context

DD-18 joined the audit with the delivery store at
`/admin/delivery-events?requestId=…`. Useful, but it only surfaces
the delivery half. Operators investigating a complaint or support
ticket also want the audit half:

- "Was this request even received?"
- "Which provider was it routed to and when?"
- "What was the recipient summary?" (PII-masked form already stored
  by `NotificationAudit`)

Today the only way is the audit service backend's own UI / query
tool, which fragments the operational picture across tools.

## Decision

Two new admin endpoints surface the audit record:

```
GET /admin/audit/{requestId}
GET /admin/audit/recent?tenantId=acme&limit=50
```

The single-record form looks up by request id; the recent form returns
the most-recent N audit rows for a tenant. Both return PII-safe shapes
(recipient already masked at write time by DD-07).

### SPI changes

`NotificationAuditService` gains one default method:

```java
default Optional<List<NotificationAudit>> findRecent(String tenantId, int limit) {
    return Optional.empty();
}
```

Default is `Optional.empty()` so the existing `NoOpAuditService` and
any operator-supplied audit backends compile unchanged. Backends that
naturally support a recent-listing operation (the `persistence-api`
ones, mostly) override.

The controller maps `Optional.empty()` to a `200` response with
`entries: null` and a `message` explaining "audit backend does not
support recent listing; query the backend directly" — same shape
`DeadLetterStore.snapshot()` uses (DD-13 §"Returns Optional.empty()").

### Endpoint shapes

`GET /admin/audit/{requestId}`:

- `200` with the audit record (mapped via the same redaction the
  DLQ admin endpoint uses).
- `404` when not found (covers `NoOpAuditService` returning empty,
  same overload as DD-18).

`GET /admin/audit/recent`:

- `200` with `entries` populated when the backend supports the
  listing.
- `200` with `entries: null` + a `message` when the backend
  returned `Optional.empty()`.
- `400` when `tenantId` is missing or blank — recent-audit across
  tenants is dangerous in the same way bulk-DLQ-replay across tenants
  is (DD-19).

### Why not extend the existing `/admin/configuration` family

`/admin/configuration` is for *static config* — tenants, channels,
providers. Audit records are *runtime state*. Mixing them muddles
the URL space. `/admin/audit/...` is the right home.

### Why `Optional.empty()` for the listing default

A no-op audit service has no "recent" to list — returning an empty
list would be misleading ("looks like nothing happened"), while
`Optional.empty()` plus a UI message ("audit backend doesn't support
this") is honest.

## Out of scope

- **Filtered listings** (by channel, status, failure code). Operators
  with that need go to the audit backend directly; we surface a flat
  recent view to cover the common "what just happened?" question.
- **Full-text search across audit content.** That's an indexed-search
  problem we don't pretend to solve.
- **Update / delete operations on audit.** Audit is append-only by
  convention. An update path would be a different DD.
- **Cross-tenant recent.** Rejected — see `?tenantId` requirement.

## Reasoning

### Why split into single + recent rather than one endpoint

The single-record form is a fast point-lookup; the recent form is
a listing. Conflating them with a "with no path param, return recent"
would be cute but force operators to handle two response shapes from
one URL — bad ergonomics.

### Why no pagination cursor

A `?limit=50` with no cursor means the operator sees the most recent
50, repeated calls return the same window. That's fine for the
"what just happened?" workflow this endpoint serves. A deep paging
need is again the audit backend's own problem.

### Why response is the raw NotificationAudit shape (not a redaction)

`NotificationAudit` already stores the recipient in masked form
(`j***@example.com`, `+1***890`) per DD-07. No further redaction is
needed; the response can be the audit record as-is. Unlike DD-13
DLQ entries (which hold the full `NotificationRequest`), audit is
already PII-safe by construction.
