# Decision 18: Joined audit ↔ delivery query

## Status: DECIDED

## Context

DD-17 shipped the persistent `DeliveryEventStore` and an admin
endpoint that queries by `providerName + providerMessageId`. Useful,
but operators don't know the `providerMessageId` off the top of their
head — they know the `requestId` (it's what they returned to the
caller, what shows up in support tickets, and what's in the audit
record). To answer "did `req-abc-123` ever get delivered?" today
operators have to:

1. Hit the audit store (or the response cache) to find the
   `providerMessageId`.
2. Hit `/admin/delivery-events` with that id.

Two-hop lookup for the most common operator question.

## Decision

Extend the existing `GET /admin/delivery-events` endpoint with a
third filter mode: `?requestId=req-abc-123`. The controller walks
`NotificationAuditService.findByRequestId(requestId)` to recover the
`providerName + providerMessageId`, then queries the
`DeliveryEventStore` exactly as the existing filter form does.

No new endpoint, no new SPI, no new bean wiring beyond injecting the
audit service into the controller. Composition of pieces that already
exist (DD-15, DD-17).

### Filter precedence

```
requestId           → audit.findByRequestId → store.findByProviderMessageId
providerName + providerMessageId → store.findByProviderMessageId
(neither)           → store.snapshot
```

`requestId` wins over `providerName` + `providerMessageId` when both
are supplied — the join is a stricter scope.

### Edge cases

The audit walk has four distinct outcomes; the endpoint surfaces each
distinctly rather than collapsing them all into "empty list":

| Audit state | Response | Status |
|-------------|----------|--------|
| Not found | `404` with `message: no audit record for requestId=…` | 404 |
| Found, `providerMessageId` is null | `200` with `entries: []` + `auditState: incomplete` | 200 |
| Found, `providerMessageId` set, no matching events | `200` with `entries: []` + `auditState: complete` | 200 |
| Found, `providerMessageId` set, events present | `200` with `entries: [...]` + `auditState: complete` | 200 |

The `auditState` field distinguishes "the send didn't complete yet" from
"the send completed but no callbacks have arrived" — operationally
different states that operators care to tell apart.

### Why the audit walk lives in the controller, not in the store

The `DeliveryEventStore` SPI is intentionally narrow: persist events,
query by `providerMessageId`. Putting an "and-also-walk-the-audit-record"
method on it would couple every store impl (in-memory, Redis,
future SQL) to the audit SPI. The controller is the right place to
compose two SPIs into one endpoint shape.

### Audit service is optional

The default audit impl is `NoOpAuditService` (DD-07) — operators who
haven't wired a real audit backend get `Optional.empty()` from
`findByRequestId`, which the controller renders as `404 no audit
record`. That's a slight overload of the 404 ("we couldn't find the
record" vs "we have no audit at all") but the operator action is the
same: "wire up your audit service if you want this query to work."
A separate 501 would be technically purer but adds a status code
operators have to learn for negligible value.

## Out of scope

- **Walk in the other direction.** "Given a `providerMessageId`, find
  the originating `requestId`" is the inverse query. Useful for
  forensics (a provider sent us a status callback we can't recognise)
  but rare enough that grepping the audit store directly is fine.
- **Joined response for snapshot mode.** The snapshot form
  (`/admin/delivery-events` with no filters) doesn't include the
  per-event `requestId` because that requires a reverse-walk per
  event — N audit lookups per page. Operators who need that field
  query by `requestId` explicitly.
- **Caching.** The audit lookup is a single SPI call per request and
  the implementation is operator-chosen — caching here would just
  duplicate state operators already cache at their audit layer.

## Reasoning

### Why extend the endpoint rather than add a new one

Adding `/admin/delivery-events/by-request/{requestId}` was the
alternative. Considered and rejected:

- The response shape is identical to the existing filter form. A
  separate endpoint would just be a different URL returning the same
  JSON.
- The filter mode is naturally orthogonal — operators learn one
  endpoint, three filter modes. A separate URL is more API surface
  to document and stabilise.
- The path parameter form (`/by-request/{id}`) conflicts subtly with
  REST conventions where `{id}` usually identifies a *resource of
  this collection*. A delivery event is not identified by its
  originating notification's request id — they're a 1-to-N relation.

### Why `auditState` rather than always returning entries

A caller seeing `entries: []` doesn't know whether to retry the
query in 30 seconds (send still in flight) or move on (send completed
but no delivery callback received yet). The two-state field gives
them that signal without making them parse other fields.

### Why not include the audit record in the response

Considered echoing `audit: {...}` alongside `entries: [...]` so the
operator sees both halves of the join. Decided against — `/admin/audit`
endpoint (if/when it exists) is the right place for audit content.
Mixing the two would tempt operators to use this endpoint for "show me
the audit" workflows, which is not what it's for.
