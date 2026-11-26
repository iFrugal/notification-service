# Decision 26: WhatsApp Channel and the Meta Cloud API Provider

## Status: DECIDED

## Context

Up to 1.2.x the library had a `WhatsAppProvider` SPI and a `WhatsAppRecipient` record, but no way to actually send a WhatsApp message.

- No provider module existed.
- The send pipeline always rendered a FreeMarker template before calling the provider, so a WhatsApp send without a `.ftl` failed with `TemplateNotFoundException`, although a business-initiated WhatsApp message is an approved template with parameters and has nothing to render.
- Delivery statuses stopped at `DELIVERED`; WhatsApp also reports `sent` and `read`.

Business-initiated WhatsApp messages are templates approved at the provider, addressed by name and language, with positional text parameters for the header and body and dynamic parts for some buttons.
WhatsApp itself sets rules for those parameters (no new lines, no long runs of spaces, a length limit), whichever vendor carries the message.
Meta's WhatsApp Cloud API is the reference vendor; others (MSG91, Twilio, and so on) resell the same platform with their own APIs.

## Decision

### Two modules: vendor-neutral channel, vendor-specific provider

| Module | Contents |
|--------|----------|
| `whatsapp-core` | `WhatsAppTemplateRegistry` SPI and its YAML default, `WhatsAppTemplateMessage`, `WhatsAppParameterRules`, the `WHATSAPP` `ChannelContentRenderer` |
| `whatsapp-provider-meta` | `MetaWhatsAppProvider`, `MetaHttpTransport`, the error classification, `MetaCredentialsProvider`, `MetaWebhookHandler`, native hints |

Both sit under `notification-channels/notification-channel-whatsapp`, are listed in the BOM, and do nothing until configured.
A second vendor is a sibling module, for example `whatsapp-provider-msg91`, that sends the same `WhatsAppTemplateMessage` and contributes its own webhook handler; nothing in the API, the core or `whatsapp-core` changes.

### Content: a template registry instead of FreeMarker

- `notification-core` gains a per-channel hook, `ChannelContentRenderer` (`Channel channel(); RenderedContent render(NotificationRequest)`).
  The pipeline uses the renderer registered for the request's channel and falls back to the template engine; channels without a renderer behave exactly as before.
  The renderer runs where the template engine runs: with `notification.template.validate-before-dispatch` (DD-27) before the idempotency claim, the rate limit and the audit, otherwise on the dispatch path.
  Two renderers for one channel fail the startup, naming the channel and both classes.
- `RenderedContent` gains an optional `payload` component (new canonical constructor, the 4-argument constructor and the factories are kept) and a typed `payload(Class)` accessor.
- `WhatsAppTemplateRegistry.find(tenantId, templateId)` maps the caller's logical id (`templateId`, else `notificationType`) onto a `WhatsAppTemplateBinding`: the approved name, the language, and the `templateData` keys that fill the header, body and button placeholders in order.
  The tenant's entry wins, then the `default` tenant's, like the `.ftl` lookup.
  The default implementation binds `notification.whatsapp.templates.<tenant>.<templateId>` and fails the startup on an incomplete entry.
- The renderer reads each named variable, applies `WhatsAppParameterRules` (present and not blank; no new line, carriage return or tab; no run of 5 or more spaces; at most 1024 characters) and returns the `WhatsAppTemplateMessage` as the payload.
  A bad value is a 1.2.1 `TemplateRenderException` (DD-27): `MODEL_INVALID` when it is missing or not text, and the new reason `PARAMETER_INVALID` (`TEMPLATE_PARAMETER_INVALID`, HTTP 422) when it breaks a rule.
  Its `variablePath` is the `templateData` key, or the recipient field (`recipient.languageCode`, `recipient.bodyParameters[0]`, `recipient.buttonParameters[1].index`), so a caller can point at the field; the message names the template and the variable, never the value.
  A missing binding falls through to the template engine, which raises `TemplateNotFoundException` when there is no `.ftl` either.
  With `validate-before-dispatch` on, both are thrown to the caller with nothing claimed, counted or stored (REST 422, or 404 for `TemplateNotFoundException`; Kafka one WARN and a commit); with it off they are a `FAILED` response, like every template failure.
- Backward compatible: a `WhatsAppRecipient` whose `whatsappTemplateName` is set bypasses the registry and is sent with its own language and parameter lists, as the record always promised.

### `whatsapp-provider-meta`

- `POST {base-url}/{api-version}/{phone-number-id}/messages` with `messaging_product: whatsapp`, `recipient_type: individual`, `to` (E.164 without the `+`), `type: template` and the header, body and button components.
  `biz_opaque_callback_data` carries the library request id, so a status webhook can be correlated before the message id is stored.
- Settings per tenant (provider properties): `base-url` (default `https://graph.facebook.com`), `api-version` (default `v25.0`), `timeout` (default 10s), `phone-number-id`, `access-token`, `waba-id`, `pair-rate-limit-retry-after` (default 6s).
  The API version is pinned on purpose: a library upgrade must not silently move an application to another Graph API version.
- JSON is built and read with the Jackson tree model only; the transport is the JDK `HttpClient` behind `MetaHttpTransport`, as in the FCM provider (DD-24).
- No retry loop in the provider.
  It classifies and returns, and the library's `RetryExecutor` retries with its per-channel rule and the Retry-After hint (DD-13, DD-23, DD-25).

### Error classification

Graph error codes are checked first, because Meta reports most of them under HTTP 400; the HTTP status decides only when the code is not listed.

| Condition | FailureType | errorCode |
|-----------|-------------|-----------|
| Connect failure, DNS, refused, connect timeout (nothing was sent) | TRANSIENT | `CONNECT_FAILED` |
| Read timeout, connection lost after the request, interrupted, 2xx without `messages[0].id` | AMBIGUOUS | `NO_CONFIRMATION` |
| Code 131056 (pair rate limit: same recipient too fast) | TRANSIENT, retry after max(Retry-After, `pair-rate-limit-retry-after`) | `PAIR_RATE_LIMITED` |
| HTTP 429, codes 4, 80007, 130429 | TRANSIENT, Retry-After | `THROTTLED` |
| Codes 368, 131031, 131042 (account restricted, locked, payment) | PERMANENT, health DOWN | `ACCOUNT_RESTRICTED` |
| Code 131047 (customer service window closed) | PERMANENT | `REENGAGEMENT_REQUIRED` |
| Code 131026 (recipient cannot receive the message) | PERMANENT | `RECIPIENT_UNDELIVERABLE` |
| Codes 132000 to 132999 (template missing, paused, disabled, parameter mismatch) | PERMANENT | `TEMPLATE_REJECTED` |
| HTTP 401, 403, codes 190, 10, 200 (token invalid, expired or lacking permission) | PERMANENT, health DOWN | `CREDENTIAL_REJECTED` |
| HTTP 5xx, 408, codes 1, 2, 131000, 131016, 133004 | TRANSIENT, Retry-After | `PROVIDER_UNAVAILABLE` |
| Anything else | PERMANENT | `REJECTED` |

Before any HTTP call: `INVALID_RECIPIENT` (not a WhatsApp recipient with an E.164 number), `TEMPLATE_REQUIRED` (the content has no template message) and `CREDENTIALS_UNAVAILABLE` (the credentials provider threw; TRANSIENT).
The errorCode set is a documented contract (`MetaErrorCodes`), so a caller can map "nothing was sent, try later" (`CONNECT_FAILED`, `THROTTLED`, `PAIR_RATE_LIMITED`, `PROVIDER_UNAVAILABLE`, `CREDENTIALS_UNAVAILABLE`) to 503 or 429.
The provider's Retry-After reaches the caller as `NotificationResponse.retryAfter` (DD-31).
`idempotency.failure-policy: RELEASE_TRANSIENT` (DD-32) releases the idempotency key of the `TRANSIENT` failures; a deployment that also wants a retry under the same key after a token fix declares its own `IdempotencyReleasePolicy` bean that releases `CREDENTIAL_REJECTED`.
A health DOWN state ends with the next successful send.
Logs never contain the access token, the recipient's number or a template value; Graph error text goes through `PiiMasking.redact`.

### Credentials: a per-provider SPI

- `MetaCredentialsProvider` (functional interface): `MetaCredentials resolve(String tenantId)`, returning the access token, the phone number id and an optional WABA id; `MetaCredentials.toString()` never prints the token.
- It is called on every send, so an implementation can read a secret store or a database and cache with its own TTL: per-tenant business accounts and token rotation without a restart.
- Discovery follows the ACS credential factory and the FCM token-provider factory: a Spring bean of the type wins; without one, the provider uses the static `access-token` and `phone-number-id` of the tenant's provider properties.

### Status webhook

`MetaWebhookHandler` is a `WebhookHandler` (DD-36), registered with `notification.webhooks.meta.enabled=true` under the id `meta` (configurable, so several Meta apps can each have one).

- `GET` with `hub.mode=subscribe`: a constant-time comparison of `hub.verify_token` with `verify-token`, then `hub.challenge` echoed as `text/plain`; otherwise 403.
- `POST`: `X-Hub-Signature-256: sha256=<hex>` must equal the HMAC-SHA256 of the raw body keyed with `app-secret` (constant-time), otherwise 403.
  `signature-verification: false` turns this off and logs a WARN on every callback, as for Twilio and SES.
- `entry[].changes[].value.statuses[]` map to `sent` SENT, `delivered` DELIVERED, `read` READ, `failed` with code 131026 BOUNCED, other `failed` FAILED_AT_PROVIDER (reason `<code> <title>`), anything else UNKNOWN.
- Provider `meta`, provider message id the `wamid`, provider event id `wamid:status:timestamp` (Meta redelivers; listeners dedupe on it), timestamp from the status's Unix seconds.
- Attributes: `status`, `phone_number_id`, `biz_opaque_callback_data`, `error_code`, `error_title`, `pricing_category`, `conversation_category`.
  `recipient_id` is the customer's phone number and is never copied or logged.
- Inbound customer messages (`value.messages`) are counted and ignored.
- A verified body is always answered 200, even with nothing mappable, because Meta retries any other answer for days; only a body that is not a JSON object is 400.

### Statuses

`DeliveryStatus` gains `SENT` and `READ`, appended after `UNKNOWN` so the ordinals of existing constants do not move; readers that predate them read `UNKNOWN` (1.1.2 precedent).
`READ` can arrive before `DELIVERED`; a consumer that projects events onto a current status must never move it backwards (see DD-16).

### Native image

The provider is instantiated by name when a tenant uses `fqcn`, so the module registers a `RuntimeHintsRegistrar` for it (the core catalog's hints cover it as well) and for the binding of `MetaWebhookProperties`; `whatsapp-core` does the same for `WhatsAppProperties`.
No POJO is serialised by reflection.

## Out of scope

- **Free-form text messages** inside the 24-hour customer service window (`type: text`).
  The design leaves room for it: with no template the renderer falls through to FreeMarker, and a provider could send the rendered text.
  The Meta provider answers `TEMPLATE_REQUIRED` for now; it is built when a caller needs it.
- **Inbound messages.** They are counted and dropped; an inbound-message SPI is a separate decision.
- **Other vendors.** MSG91 or Twilio WhatsApp come as sibling modules (`whatsapp-provider-<vendor>`) that consume `WhatsAppTemplateMessage` and add their own webhook handler.
- **A cross-provider credential SPI.** Every provider's credential has another shape (an ACS `TokenCredential`, an FCM OAuth token, a Meta bearer token plus a sender id); a shared SPI would be a `Map<String, Object>` without type safety.
  Revisit when a third provider needs the same shape.
- **Named template parameters** (`parameter_name`), media headers, location and interactive messages.
- **A per-recipient rate limiter.** Pair rate limits (131056) are handled by classification and the retry executor.
- **Consent and opt-in tracking**, which belong to the application.

## Reasoning

### Why resolve the template in a renderer, not in the provider

Resolving the binding inside the provider would validate the request only after the idempotency claim and the rate-limit token were taken, and every vendor module would have to repeat the registry lookup and the parameter rules.
As a renderer, the resolution runs where FreeMarker runs, once for all vendors, and before the claim when `validate-before-dispatch` (DD-27) is on.

### Why rule violations are TemplateRenderExceptions

1.2.1 already answers a value that does not fit a template: `TemplateRenderException` carries a reason, the template id and the variable path, REST maps it to 422 with that body, the Kafka listener rejects it with one WARN, and a metric counts it by reason.
A separate WhatsApp exception would need its own REST mapping, Kafka handling and metric, and callers would handle two shapes of the same client error.
`MODEL_INVALID` already means "a missing or wrongly typed value"; a present value that breaks a channel's rule is a different fix for the caller, so it gets its own reason, `PARAMETER_INVALID`, which any later channel with parameter rules can reuse.

### Why errorCodes are a contract

Callers must decide between "retry later", "do not retry" and "may have been sent" without parsing Meta's messages, which change.
Fixed codes, pinned by a test per row, let an application map them to its own API once.

### Why health DOWN on credential and account errors

Every later send will fail the same way until a human fixes the token or the account; a DOWN health indicator turns a stream of failed sends into one alert.
It clears on the next success, so a rotated token restores it without a restart.

### Why no retry loop in the provider

The library already has one retry loop with per-channel rules, Retry-After handling and a dead-letter store.
A second loop inside the provider would multiply attempts and hide them from the metrics and the DLQ.
`AMBIGUOUS` is not retried by default, because the Cloud API has no idempotency key and a resend could deliver the message twice.
