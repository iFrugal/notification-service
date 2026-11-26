# whatsapp-provider-meta

WhatsApp provider for Meta's [WhatsApp Cloud API](https://developers.facebook.com/docs/whatsapp/cloud-api) (Graph API), with its status webhook.
Provider name `meta` on the `WHATSAPP` channel.
Available since 1.4.0.
See [DD-35](../../../docs/design-decisions/35-whatsapp-channel-and-meta-provider.md).

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>whatsapp-provider-meta</artifactId>
    <version>${notification-service.version}</version>
</dependency>
```

It brings [`whatsapp-core`](../whatsapp-core/README.md), which maps your template ids onto approved templates.
No HTTP library and no Meta SDK: calls go through `java.net.http`, the webhook signature is checked with the JDK, and JSON uses the Jackson 2 tree model that `notification-api` already depends on.

## Configuration

Like every provider in this library, Meta is configured per tenant; `ProviderRegistry` creates one instance per tenant and calls `configure(properties)`, then `init()`.
Neither calls the network.

```yaml
notification:
  tenants:
    default:
      channels:
        whatsapp:
          enabled: true
          providers:
            meta:
              properties:
                phone-number-id: ${WHATSAPP_META_PHONE_NUMBER_ID}  # <phone-number-id>, not the phone number
                access-token: ${WHATSAPP_META_ACCESS_TOKEN}        # system user token with whatsapp_business_messaging
                # waba-id: <waba-id>                               # optional, informational
                # api-version: v25.0                               # default, pinned
                # base-url: https://graph.facebook.com             # default
                # timeout: 10s                                     # default, per request
                # pair-rate-limit-retry-after: 6s                  # default, least wait after error 131056

  whatsapp:
    templates:
      default:
        order-shipped:
          name: order_shipped
          language: en
          body: [customerName, orderNumber]
```

| Property | Default | Meaning |
|----------|---------|---------|
| `phone-number-id` | - | The sender's phone number id from the WhatsApp Manager; required without a `MetaCredentialsProvider` |
| `access-token` | - | Bearer token; required without a `MetaCredentialsProvider` |
| `waba-id` | - | WhatsApp Business Account id, optional |
| `api-version` | `v25.0` | Graph API version. Pinned: upgrading the library never moves you to another version; change it when you have tested the new one |
| `base-url` | `https://graph.facebook.com` | https, or http to a loopback address (tests) |
| `timeout` | `10s` | Per request; ISO-8601 or `500ms`, `10s`, `5m` |
| `pair-rate-limit-retry-after` | `6s` | Least retry delay after a pair rate limit (error 131056) |

## Credentials per tenant

Declare a `MetaCredentialsProvider` bean to supply the token and the sender per tenant, for example from a secret store, or to rotate tokens without a restart:

```java
@Bean
MetaCredentialsProvider metaCredentials(SecretStore secrets) {
    return tenantId -> new MetaCredentials(
            secrets.get("whatsapp/" + tenantId + "/access-token"),
            secrets.get("whatsapp/" + tenantId + "/phone-number-id"));
}
```

It is called on every send, so cache with your own TTL.
When the bean exists, `access-token` and `phone-number-id` in the properties are ignored (a WARN says so).
If it throws, the send fails with `CREDENTIALS_UNAVAILABLE` (`TRANSIENT`, nothing was sent).
`MetaCredentials.toString()` never prints the token.

## What is sent

A template message, `POST {base-url}/{api-version}/{phone-number-id}/messages`:

```json
{"messaging_product":"whatsapp","recipient_type":"individual","to":"15550100001","type":"template",
 "template":{"name":"order_shipped","language":{"code":"en"},"components":[
   {"type":"body","parameters":[{"type":"text","text":"Jane"},{"type":"text","text":"1042"}]},
   {"type":"button","sub_type":"url","index":"0","parameters":[{"type":"text","text":"abc123"}]}]},
 "biz_opaque_callback_data":"<request id>"}
```

A `quick_reply` button carries a `payload` parameter and a `copy_code` button a `coupon_code` one.
The library request id goes out as `biz_opaque_callback_data` and comes back on every status callback.
On success the provider message id is the `wamid`.
Free-form text messages are not supported yet: content without a template message fails with `TEMPLATE_REQUIRED`.

## Failure classification and retries

The provider never retries; it classifies and returns, and the library's retry executor (`notification.retry.*`, per channel under `by-channel.whatsapp`) decides, honouring Retry-After.
The `errorCode` values are a contract (`MetaErrorCodes`):

| errorCode | FailureType | When |
|-----------|-------------|------|
| `CONNECT_FAILED` | TRANSIENT | No connection: DNS, refused, connect timeout. Nothing was sent |
| `NO_CONFIRMATION` | AMBIGUOUS | Read timeout, connection lost, interrupted, or 2xx without a message id. Meta may have accepted it; not retried by default |
| `THROTTLED` | TRANSIENT + Retry-After | HTTP 429, error codes 4, 80007, 130429 |
| `PAIR_RATE_LIMITED` | TRANSIENT + max(Retry-After, `pair-rate-limit-retry-after`) | Error 131056: too many messages to the same recipient |
| `PROVIDER_UNAVAILABLE` | TRANSIENT + Retry-After | HTTP 5xx, 408, error codes 1, 2, 131000, 131016, 133004 |
| `CREDENTIALS_UNAVAILABLE` | TRANSIENT | Your `MetaCredentialsProvider` threw |
| `CREDENTIAL_REJECTED` | PERMANENT, health DOWN | HTTP 401, 403, error codes 190, 10, 200 |
| `ACCOUNT_RESTRICTED` | PERMANENT, health DOWN | Error codes 368, 131031, 131042 |
| `REENGAGEMENT_REQUIRED` | PERMANENT | Error 131047: the customer service window is closed |
| `RECIPIENT_UNDELIVERABLE` | PERMANENT | Error 131026 |
| `TEMPLATE_REJECTED` | PERMANENT | Error codes 132000 to 132999: template missing, paused, disabled or parameter mismatch |
| `REJECTED` | PERMANENT | Any other refusal |
| `INVALID_RECIPIENT` | PERMANENT | Not a `WhatsAppRecipient` with an E.164 number (before sending) |
| `TEMPLATE_REQUIRED` | PERMANENT | No template message in the content (before sending) |

"Nothing was sent, try again later" is `CONNECT_FAILED`, `THROTTLED`, `PAIR_RATE_LIMITED`, `PROVIDER_UNAVAILABLE` and `CREDENTIALS_UNAVAILABLE`; an API in front of the library can map them to 503 or 429.
After `CREDENTIAL_REJECTED` or `ACCOUNT_RESTRICTED` the provider's health is DOWN, with `lastError` in its details, until a send succeeds.
A Retry-After hint is also returned to the caller as `NotificationResponse.retryAfter`.

### Idempotency after a credential error

`notification.idempotency.failure-policy: RELEASE_TRANSIENT` releases the idempotency key after the `TRANSIENT` failures above, so a retry under the same key is sent again, and keeps it after `NO_CONFIRMATION`, which Meta may have accepted.
`CREDENTIAL_REJECTED` is `PERMANENT`, so its key is kept, although nothing was sent.
A deployment that wants a retry under the same key to go out once the access token is fixed declares its own `IdempotencyReleasePolicy` bean:

```java
@Bean
IdempotencyReleasePolicy releasePolicy(NotificationProperties properties) {
    IdempotencyReleasePolicy configured = new FailurePolicyReleasePolicy(properties);
    return (request, failed) -> configured.releases(request, failed)
            || (failed.channel() == Channel.WHATSAPP
                && MetaErrorCodes.CREDENTIAL_REJECTED.equals(failed.errorCode()));
}
```

It replaces the library's policy, so it delegates to `FailurePolicyReleasePolicy` to keep the configured `failure-policy` for every other failure.

## Status webhook

```yaml
notification:
  webhooks:
    enabled: true                     # the REST route; not needed when you mount the dispatcher yourself
    meta:
      enabled: true
      app-secret: ${META_APP_SECRET}           # your-app-secret: signs every POST
      verify-token: ${META_VERIFY_TOKEN}       # your-verify-token: any value, entered in the Meta app
      # signature-verification: true           # keep on in production
      # id: meta                               # path segment; one handler per Meta app
```

With the REST module, register `https://<your host>/api/v1/webhooks/meta` as the callback URL in the Meta app and subscribe to the `messages` field.
Without it, route `GET` and `POST` to `WebhookDispatcher.dispatch("meta", request)` yourself, passing the raw body bytes (see [DD-36](../../../docs/design-decisions/36-webhook-handler-spi.md)).

- `GET` with `hub.mode=subscribe` and the right `hub.verify_token` echoes `hub.challenge`; anything else is 403.
- `POST` must carry `X-Hub-Signature-256: sha256=<hex>`, the HMAC-SHA256 of the raw body with the app secret; otherwise 403.
- Statuses become delivery events for your `DeliveryEventListener`s and the delivery event store:

| Meta `status` | `DeliveryStatus` |
|---------------|------------------|
| `sent` | `SENT` |
| `delivered` | `DELIVERED` |
| `read` | `READ` |
| `failed` with error 131026 | `BOUNCED` |
| `failed`, other errors | `FAILED_AT_PROVIDER`, reason `<code> <title>` |
| anything else | `UNKNOWN` |

Each event has provider `meta`, the `wamid` as provider message id and `wamid:status:timestamp` as provider event id (Meta redelivers; dedupe on it), plus the attributes `status`, `phone_number_id`, `biz_opaque_callback_data`, `error_code`, `error_title`, `pricing_category` and `conversation_category` when present.
The recipient's phone number is never copied into an event or a log line.
`read` can arrive before `delivered`: never move a projected status backwards.
Inbound customer messages are counted in a log line and ignored.
A verified callback is always answered 200, even when nothing in it maps, because Meta retries any other answer.

## HTTP client

The default transport is one JDK `HttpClient` per JVM (no redirects, default proxy selector).
Declare a single `MetaHttpTransport` bean to replace it, for example to add a proxy or metrics.
Implementations must not retry.

## Logging and secrets

Logs never contain the access token, the app secret, the verify token, the recipient's number or a template value.
Graph error text is passed through `PiiMasking.redact` and truncated.

## GraalVM native image

`MetaWhatsAppRuntimeHints` registers the provider class (instantiated by name with `fqcn`) and the binding of `MetaWebhookProperties`.
JSON uses the Jackson tree model only, so no POJO needs reflection hints.
