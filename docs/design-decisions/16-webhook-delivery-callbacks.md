# Decision 16: Webhook Delivery Callbacks

## Status: DECIDED

## Context

The notification service today surfaces "did the provider accept this
request" via the `NotificationStatus.SENT` state and the
`providerMessageId` returned by `SendResult.success(...)`. That's
where its visibility ends. After accept-time:

- **SES** can deliver, bounce, or generate a complaint hours later. We
  don't know which — the customer support inbox does.
- **Twilio SMS** can be queued at the carrier, undelivered, or
  delivered after a multi-second delay. Status updates via webhook are
  the only push-side signal.
- **FCM** doesn't expose per-message delivery callbacks (delivery data
  is BigQuery-export-only as of late 2025). We note this rather than
  pretend to support it.

Callers asking "did this notification actually reach the user?" today
have to query the provider directly with the `providerMessageId` we
returned, which fragments the operational picture. Worse, *we* can't
update our own audit trail, so a recurring "delivery rate" report
either wires into every provider's dashboard separately or rolls a
custom feedback handler.

This DD adds an opt-in webhook surface that ingests provider
delivery callbacks and reflects them as **delivery events** on the
audit record, joined by `providerMessageId`.

## Decision

Add a new opt-in `notification-rest` surface under `/webhooks/{provider}/{path}`
that accepts provider-side delivery callbacks, verifies the signature
per provider, and dispatches the parsed event to a
`DeliveryEventListener` SPI. The default listener is a no-op; operators
who want delivery state in their audit store wire in their own
listener (or use the future `DeliveryEventStore` SPI a follow-up DD
can ship).

The feature is **off by default**. Each provider integration is
independently toggleable so an SES-only deployment doesn't have to
configure Twilio's auth token to satisfy startup validation.

### Endpoint shape

```
POST /webhooks/twilio/status
POST /webhooks/ses/sns
```

Each handler verifies the provider's signature scheme **before**
parsing the body. A failed verification returns `403 Forbidden` and
logs the source IP; the request is *not* retried by us — the provider
will retry on its own retry timer if it cares.

The shape of the body is provider-specific:

- **Twilio status callback** — `application/x-www-form-urlencoded`,
  fields documented at <https://www.twilio.com/docs/usage/webhooks/sms-webhooks>.
  Verification: HMAC-SHA1 of `URL + sorted(form-params)` keyed by the
  account auth token, compared to the `X-Twilio-Signature` header.
- **SES via SNS** — `application/json`, two relevant `Type` values:
  `SubscriptionConfirmation` (one-time, on topic subscribe) and
  `Notification` (every event). Verification: AWS-SDK-side via
  `MessageManager.parseMessage(...)` which handles the X.509 +
  signing-cert URL dance. We cache the cert per signing-cert URL with
  a small Caffeine cache to avoid re-fetching for every callback.

### State model

A new enum `DeliveryStatus` lives in `notification-api`:

```java
public enum DeliveryStatus {
    /** Provider confirms the message reached the recipient device / inbox. */
    DELIVERED,
    /** Hard bounce — recipient address is invalid or undeliverable. */
    BOUNCED,
    /** Recipient complained (SES "complaint", Twilio "delivery_failed_carrier" subset). */
    COMPLAINED,
    /** Provider gave up after its own retries. */
    FAILED_AT_PROVIDER,
    /** Catchall — provider sent something we couldn't classify. */
    UNKNOWN
}
```

Each callback becomes a `DeliveryEvent`:

```java
public record DeliveryEvent(
    Instant timestamp,
    String providerName,
    String providerMessageId,
    String providerEventId,    // for dedup; e.g. SNS MessageId, Twilio AccountSid+SmsSid+Status
    DeliveryStatus status,
    String reason,             // provider-supplied error description, may be null
    Map<String, String> attributes // raw provider fields, for audit forensics
) { ... }
```

### Idempotency on the inbound side

Providers retry callbacks. The same `(providerName, providerMessageId, status)`
tuple arriving twice should not double-count. The
`DeliveryEventListener` SPI is responsible for its own dedup using the
`providerEventId` field — implementations that persist to a database
should treat it as a unique key.

### SPI

```java
public interface DeliveryEventListener {
    /**
     * Notify the listener of a parsed and signature-verified
     * delivery callback. Implementations must not throw — failure
     * to handle the event must not propagate to the webhook endpoint
     * (the provider would retry forever, and that's not what we want).
     */
    void onEvent(DeliveryEvent event);
}
```

A default `LoggingDeliveryEventListener` is registered when
`notification.webhooks.enabled=true` and no other listener is
configured. It logs at INFO and is good enough for "wire it up,
prove the signature path, and then plug in your real listener" as a
deployment ramp.

### Configuration

```yaml
notification:
  webhooks:
    enabled: false  # master switch, default off
    base-path: /webhooks  # under the rest base-path, so the full URL is e.g. /api/v1/webhooks/...
    twilio:
      enabled: false
      auth-token: ${TWILIO_AUTH_TOKEN}  # required when enabled
      signature-verification: true       # operators can disable for dev
    ses:
      enabled: false
      topic-arn: ${SES_TOPIC_ARN}        # required when enabled
      signature-verification: true       # AWS recommends this stays on
```

The base path inherits from `notification.rest.base-path` (default
`/api/v1`), so the deployable URL ends up `/api/v1/webhooks/twilio/status`
without operators having to specify two paths.

### Why not extend `NotificationStatus` instead of a new enum

`NotificationStatus` describes the **request lifecycle** —
`PENDING / PROCESSING / SENT / FAILED / REJECTED`. Delivery happens
*after* the lifecycle ends from the service's perspective. Conflating
them ("ok now we have `SENT_THEN_BOUNCED`") would explode the enum
combinatorially and break the existing
`response.status() == FAILED` switches in the retry / DLQ path. Better
to keep delivery state as a separate dimension.

### Why webhooks rather than periodic polling

Polling at provider-supported scale (hundreds of thousands of sends a
day) is operationally expensive — Twilio's REST API is rate-limited,
SES doesn't expose a "list deliveries since" cheap endpoint. The
provider-pushed model is what the providers themselves recommend, and
we're not in the business of being clever about it.

## Out of scope

- **Persistent `DeliveryEventStore` SPI.** Mirroring the DD-13 DLQ
  shape — bounded in-memory default, plug-in Redis/SQL/Kafka. Good
  follow-up but we ship the listener seam first; persistence is a
  matter of who's listening.
- **Webhook out for our callers.** We don't push status to upstream
  caller services; that's their reverse-webhook problem. Callers can
  subscribe to whatever event bus our `DeliveryEventListener`
  implementation publishes to.
- **FCM delivery receipts.** FCM does not offer per-message webhook
  callbacks. The `notification.webhooks.fcm.*` namespace is reserved
  but not implemented; the README calls this out so operators don't
  expect it.
- **Replay of historical events.** Providers send events once; we
  don't go re-fetch missed callbacks. If a callback is dropped
  (signature mismatch, downtime), the event is gone.
- **Webhook-driven retry triggers.** "Bounce → automatically reset
  the recipient address and retry" is a customer-product decision
  that's downstream of having events at all. Not our call to make
  here.

## Reasoning

### Why provider-namespaced paths

Single `/webhooks` with body-sniffing would be cute but every provider
needs its own signature verification scheme — Twilio's HMAC has a
totally different shape from SNS's X.509-signed envelope. The router
needs to know which scheme to apply *before* it can trust the body, so
the path is the natural place to encode that.

### Why fail-403 rather than fail-silent on signature mismatch

Two reasons. First, returning `200` to a forged callback teaches the
attacker that the endpoint exists and accepts unsigned requests —
they'll keep probing. Second, a real provider whose signing key
rotated will see the 403 and surface the failure in its own
admin dashboard; that's the recovery loop we want.

### Why the listener-not-store seam

`DeliveryEventStore` would have been the symmetric choice — same
shape as DD-10 idempotency, DD-13 DLQ. But:

- Most operators want the events fanned out to their *existing* audit
  pipeline (Kafka topic, S3 sink, ELK), not stored in a service-local
  bounded buffer.
- A listener composes — operators can register multiple listeners
  (one for metrics, one for fan-out, one for in-process state) without
  the SPI deciding the storage strategy for them.

A persistent `DeliveryEventStore` is a reasonable follow-up; for
this DD, the listener seam is the minimum that lets operators wire
the events into whatever store they already run.

### Why opt-in

Same reasoning as DD-10/DD-12/DD-13/DD-14/DD-15. Existing 1.0.x
deployments shouldn't suddenly start exposing webhook endpoints. The
master switch + per-provider toggle gives operators incremental
adoption — turn on Twilio status callbacks today, leave SES off until
the SNS topic is provisioned.
