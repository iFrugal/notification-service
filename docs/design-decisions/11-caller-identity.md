# Decision 11: Caller Identity (`X-Service-Id`)

## Status: DECIDED

## Context

DD-10 introduced first-class idempotency. Its dedup scope is intentionally
`(tenantId, callerId, idempotencyKey)` — but it shipped with `callerId`
hardcoded `null` everywhere, with the slot reserved for this DD. That gap has
two real consequences:

1. **Cross-caller key collisions.** Two services in the same tenant — say
   `billing-svc` and `marketing-svc` — that both happen to mint
   `idempotencyKey = "user-123-welcome"` will collide. One service's send is
   silently treated as a replay of the other's. In a multi-team monorepo this
   is plausible.
2. **No traceability of who sent what.** Audit records know the tenant but
   not the originating service. When operators investigate "why did this
   user get this notification at 03:14?" they can see the request id and
   tenant but cannot reconstruct which upstream service initiated it without
   correlating logs across systems.

The notification service is multi-tenant *and* multi-caller within a tenant.
The header-based pattern that worked for tenancy in DD-03 should extend
naturally to caller identity.

## Decision

Introduce **`X-Service-Id`** as a first-class HTTP request header and a
matching `callerId` field on `NotificationRequest`, propagated through the
existing `RequestContext` (from `app-building-commons`). The value flows into:

1. The idempotency dedup scope — closing the DD-10 callerId gap.
2. The audit record — `NotificationAudit.callerId`.
3. The response — `NotificationResponse.callerId` for echo-back / tracing.
4. An optional **caller registry** (config-driven allowlist) for operators
   that want strict admission control.

### HTTP header

```
X-Service-Id: billing-svc
```

- **Optional by default.** Missing header → `callerId = null` → behaviour
  matches the pre-DD-11 status quo. No existing caller breaks.
- **Max 128 characters**, ASCII only. We don't enforce a regex on the value
  itself (callers' service names vary), but we cap length to keep cache and
  audit indexes well-behaved.
- **Echoed in `NotificationResponse.callerId`.** Lets a caller confirm the
  service identified itself correctly under proxy/gateway rewriting.

### Request field

```java
// In NotificationRequest
@Size(max = 128, message = "callerId must be at most 128 characters")
private String callerId;
```

If the request body sets `callerId` directly (programmatic / Kafka path), it
wins over the header — same precedence rule DD-03 uses for `tenantId`.

### Response field

`NotificationResponse` gains a `callerId` component. Always populated with
the resolved caller id (may be `null`). Existing callers ignore unknown JSON
fields, so adding it is non-breaking.

### Audit record

`NotificationAudit.callerId` is added and populated by
`NotificationAuditService.recordReceived(...)`. This unblocks queries like
"all sends from `billing-svc` in the last hour" without log scraping.

### Idempotency scope

`DefaultNotificationService.idempotencyKeyFor(...)` now reads
`request.getCallerId()` instead of hardcoding `null`. The dedup scope
becomes the full `(tenantId, callerId, idempotencyKey)` tuple from DD-10. A
caller that *doesn't* send `X-Service-Id` continues to dedup against the
`null` slot — same scope as before this DD landed, so no regressions.

### Caller registry (optional, off by default)

```yaml
notification:
  caller-registry:
    enabled: false        # master switch
    strict: false         # if true, unknown caller-ids are rejected
    known-services:       # advisory list — used for admin/observability
      - billing-svc
      - marketing-svc
      - account-svc
```

Behaviour:

| `enabled` | `strict` | Unknown `X-Service-Id`            | Missing header              |
|-----------|----------|------------------------------------|------------------------------|
| `false`   | _n/a_    | accepted, no log                   | accepted, `callerId = null` |
| `true`    | `false`  | accepted, `WARN`-level log         | accepted, `callerId = null` |
| `true`    | `true`   | **rejected with HTTP 403**         | accepted, `callerId = null` |

The default off-state is deliberate: most deployments will roll out caller
identity gradually, so we can't make missing headers an error. Strict mode
is the opt-in for environments that have already rolled out callers
end-to-end.

### Admin endpoint

`GET /api/v1/admin/caller-registry` returns:

```json
{
  "enabled": true,
  "strict": false,
  "knownServices": ["billing-svc", "marketing-svc", "account-svc"]
}
```

Same masking and response shape as the existing `/admin/configuration`
endpoint (DD-08) — operators get a single page to inspect deployment state.

### Kafka path

The Kafka consumer reads `X-Service-Id` from the message headers analogously
to how it reads `X-Tenant-Id` (DD-03). Out of scope for this PR — Kafka
ingestion will pick up the same plumbing once it's wired; until then
Kafka-originated requests have `callerId = null`, which is the same
behaviour as today.

## Reasoning

### Why a header, not a body field only

- Symmetric with `X-Tenant-Id` from DD-03. Operators already debug with
  request headers; adding another one fits the mental model.
- Edge / API gateway can inject it from mTLS client certs or service
  account tokens without parsing the body.
- Bodies are not always available in observability tooling (NGINX access
  logs, gateway audit logs); headers are.

### Why optional, not required

A required header is a backwards-incompatible change. The existing
notification-service is in production (1.0.0 on Maven Central). Forcing a
header value on every existing caller would break them. The registry's
`strict: true` mode gives operators an explicit opt-in path once their
ecosystem is ready.

### Why a separate `callerId` rather than encoding it inside `tenantId`

`tenantId` answers "whose data are we touching?" `callerId` answers "who is
acting?" These are independent dimensions:

- An internal admin tool may act on any tenant's behalf.
- A B2B integration may act on exactly one tenant.

Conflating them would force callers to encode synthetic tenant ids and
makes the audit trail less useful.

### Why caller registry is opt-in

We'd rather have correct telemetry than lock out a service mid-deploy. The
registry is primarily a documentation and observability tool: "here are the
services we expect to see." Enforcement (`strict`) is the rare, deliberate
case.

## Consequences

### Positive

- Closes the DD-10 callerId gap — `(tenantId, callerId, idempotencyKey)`
  is now a real composite key rather than a placeholder.
- Audit trail gains a "who sent it?" dimension without requiring log
  correlation.
- Operators get a config-driven view of expected callers via the admin
  endpoint, useful during rollout.
- Header precedence matches DD-03's `X-Tenant-Id` rules — no new mental
  model.

### Negative

- One more header for callers to track. We mitigate by making it optional.
- `NotificationResponse` gains a 14th component. Record positional factories
  in the codebase had to be updated; external callers using the JSON
  contract are unaffected.

### Migration path

| Phase  | Action                                                          |
|--------|-----------------------------------------------------------------|
| Now    | DD-11 ships, header is optional, registry is off                |
| +T1    | Operators enable `caller-registry.enabled: true`, populate list |
| +T2    | Once all callers send the header, flip `strict: true`           |

## Alternatives Considered

### Alternative 1: Encode callerId inside `correlationId`

- **Rejected**: `correlationId` is opaque per its current contract. Parsing
  it for a caller id would couple unrelated concerns and break callers that
  use opaque UUIDs for correlation.

### Alternative 2: Derive `callerId` from authenticated principal

- **Rejected for now**: Couples to an auth scheme that is not specified in
  this codebase. Header-based is more portable and works in environments
  where auth is terminated at the gateway.

### Alternative 3: Make the header required from day one

- **Rejected**: Breaking change for production callers. The strict-mode
  toggle gives the same enforcement when operators are ready.

## Related Decisions

- [03-multi-tenancy.md](./03-multi-tenancy.md) — `X-Tenant-Id` header pattern
  this DD mirrors.
- [07-audit-persistence.md](./07-audit-persistence.md) — `NotificationAudit`
  gains the `callerId` field.
- [10-idempotency.md](./10-idempotency.md) — DD-11 closes the
  `callerId = null` gap left for this DD.
