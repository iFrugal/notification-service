# Decision 34: Channel-Agnostic Recipient Guard

## Status: DECIDED

## Context

Non-production environments send real notifications through real providers, often with production-like data.
Without a guard in the library every application has to filter recipients itself, before the request reaches the service, which is easy to forget in one code path and impossible for requests arriving over REST or Kafka.
The guard has to work the same way for every channel with addresses, keep the idempotency contract intact, and never leave the real addresses in persisted records.

## Decision

### SPI

`RecipientGuard` (notification-api, functional): `Decision check(NotificationRequest request)`.
`Decision` holds the recipient to send to (`null` when every address is blocked), the original addresses that were redirected, the number dropped, and an optional email subject prefix; `blocked()` and `changed()` are derived.
`RecipientGuard.allowAll()` is the behaviour without a guard.
An application bean replaces the default whether or not the property is on.

### Where it runs

In `DefaultNotificationService.send`, after the idempotency claim (so the fingerprint and the key use the original request, DD-32) and before the audit record, rendering and dispatch:

1. The guard decides on the request as the caller sent it; a request without a recipient is not guarded.
2. When the decision changed anything, a copy of the request with the guarded recipient (`toBuilder()`) is used for the audit record and the provider; the caller's request object is untouched.
3. The template is rendered from the original request (templates never see recipients).
4. A blocked decision fails the send with error code `RECIPIENT_BLOCKED` and failure type `PERMANENT`, without reaching the provider.
5. The subject prefix, when set, goes in front of the rendered subject, else in front of the recipient's own subject.
6. One INFO line per changed send logs the request id, channel and counts (redirected, dropped, blocked), never an address.

### Default: configuration-driven guard

`ConfiguredRecipientGuard`, registered with `notification.recipient-guard.enabled=true` (default `false`):

```yaml
notification:
  recipient-guard:
    enabled: true
    channels:
      email:
        allowed: ["@example.com", "qa.lead@example.org"]
        redirect-to: qa-inbox@example.com
        subject-prefix: "[redirected: {originals}] "   # default; "" disables
      sms:
        allowed: ["+15550100"]                         # no redirect-to: drop mode
```

- `@domain` allows every email address in exactly that domain, case-insensitively (subdomains are not included).
- Any other pattern is one email address (case-insensitive) or one phone number compared on its digits only (`+1 555-0100` equals `15550100`).
- A recipient that is not allowed is replaced one-for-one by `redirect-to`; without `redirect-to` it is dropped.
- Email addresses are de-duplicated across to, cc and bcc, case-insensitively, `to` first; when `to` itself is dropped, the first remaining cc (else bcc) address becomes `to`.
- Nothing left: blocked.
- The email subject prefix applies only when something was redirected; `{originals}` is the comma-separated list of the redirected addresses.
- The guard works on the address strings of the sealed recipient types: email to/cc/bcc, the SMS and WhatsApp phone number (other WhatsApp fields are kept).
  Push recipients are out of scope and pass unchanged; a `push` rule is ignored with a WARN.
- A channel without rules is not guarded.

Startup validation: an unknown channel name fails startup; a `redirect-to` that is not itself allowed fails startup; `allowed` without `redirect-to` is valid (drop mode).

### What is persisted

The audit record (and therefore the status view, DD-33) is written from the guarded request: its masked summary and recipient count describe where the message really went.
The idempotency fingerprint is a hash of the original request; no original address is stored in it.
Original addresses reach only the email subject prefix of the redirected message and are never logged.
The dead-letter store, as before, keeps the full original request so a replay goes through the guard again.

## Reasoning

### Why after the claim and not at admission

The idempotency contract is about what the caller asked for; guarding before the fingerprint would make the same request hash differently per environment.
After the claim, a blocked send is a normal `FAILED` outcome that the key records and the status view shows.

### Why redirect one-for-one and de-duplicate

Replacing each address keeps the shape of the message (the redirect inbox sees that there were several recipients through the prefix), and de-duplication keeps the inbox from receiving several copies of one send.

### Why PERMANENT for a blocked send

Retrying cannot change the configuration; under `RELEASE_TRANSIENT` the key keeps the blocked outcome and replays it.

### Why an SPI with a configuration default

Rules such as "allow only users flagged as testers in our database" cannot be expressed in properties; the SPI lets an application plug them in while the default covers the common allow-list case.

## Consequences

### Positive

- One switch keeps non-production sends inside an allow-list for email, SMS and WhatsApp, for every entry point.
- The idempotency contract and the status view stay consistent with what was really sent.
- Misconfiguration fails at startup.

### Negative

- Push is not guarded.
- Promoting a bcc address into `to` makes it visible to cc recipients; in a guarded environment those are all allowed addresses.
- The dead-letter store still holds the original addresses, as it always did.

## Related Decisions

- [07-audit-persistence.md](./07-audit-persistence.md) - masked audit records
- [32-idempotency-fingerprint-failure-policy.md](./32-idempotency-fingerprint-failure-policy.md) - fingerprint of the original request
- [33-status-view.md](./33-status-view.md) - the view built from the guarded record
