# notification-service 1.4.0

1.4.0 builds on 1.3.0: everything in the [1.3.0 release notes](./RELEASE_NOTES_1.3.0.md) applies, and a 1.2 deployment upgrading to 1.4.0 should read those first.
The WhatsApp channel uses 1.3.0's send pipeline as it is: validate before dispatch, the idempotency fingerprint and release policy, the recipient guard, the status view, lazy provider initialisation and the rate-limit bands.

## Highlights

- **WhatsApp through the Meta Cloud API.**
  `whatsapp-provider-meta` sends approved template messages over the Graph API, with no HTTP library and no Meta SDK (DD-35).
  Every failure carries a documented error code and a `FailureType`, and the provider leaves retries to the library's retry executor.
- **Template bindings instead of FreeMarker for WhatsApp.**
  `whatsapp-core` maps your template ids onto the templates approved at the provider and fills their parameters from `templateData`, enforcing WhatsApp's parameter rules before anything is sent.
- **WhatsApp statuses come back as delivery events.**
  The Meta webhook verifies the subscription handshake and the `X-Hub-Signature-256` signature and maps `sent`, `delivered`, `read` and `failed` onto delivery events; `DeliveryStatus` gains `SENT` and `READ`.
- **Webhooks are pluggable.**
  A servlet-free `WebhookHandler` SPI and a core `WebhookDispatcher` replace the hardcoded webhook controller (DD-36).
  Twilio and SES keep their paths; an application without the REST module can now ingest callbacks from its own endpoint.
- **Per-channel content renderers.**
  `ChannelContentRenderer` lets a channel render its own content instead of the FreeMarker engine; channels without one are unchanged.
  With `notification.template.validate-before-dispatch` (1.3.0) on, the renderer runs before the idempotency claim, so a WhatsApp request with bad template data is refused with nothing stored.
- **The standalone server bundles WhatsApp.**

## New modules

| Coordinates | What it is |
|-------------|------------|
| `com.github.ifrugal:whatsapp-core:1.4.0` | Template registry, parameter rules and the `WHATSAPP` renderer ([README](../notification-channels/notification-channel-whatsapp/whatsapp-core/README.md)) |
| `com.github.ifrugal:whatsapp-provider-meta:1.4.0` | The Meta WhatsApp Cloud API provider, `WHATSAPP:meta`, and its status webhook ([README](../notification-channels/notification-channel-whatsapp/whatsapp-provider-meta/README.md)) |

Both are in `notification-service-bom`.
The artifactIds `whatsapp-provider-meta` and `notification-channel-whatsapp` existed on Maven Central for versions 1.0.0 to 1.0.2 as empty artifacts with no classes.
1.4.0 is the first release of `whatsapp-provider-meta` with an implementation; do not use the older versions.

## Behaviour changes

The upgrade is additive: without the new modules on the classpath and without new configuration, nothing behaves differently, with the two exceptions below.

- **The webhook controller is one generic route.**
  `WebhookController` now maps `GET|POST <rest base path><webhooks base path>/{handlerId}/**` and delegates to the `WebhookDispatcher`.
  `/twilio/status` and `/ses/sns` keep their properties, status codes and response bodies; a wrong method, path or content type is still 405, 404 or 415.
  An unknown first segment is a 404 with a JSON body instead of Spring's default 404.
- **The Twilio webhook signs the form body only.**
  The 1.2 controller mixed query-string parameters into the parameters it verified, so a status callback URL with a query string always failed Twilio's signature scheme.
  The handler now verifies the URL (query included) plus the POST parameters, as Twilio signs them, and builds the event from the POST parameters.
- With `whatsapp-core` on the classpath, a `WHATSAPP` request whose recipient names a template (`whatsappTemplateName`) or whose template id has a binding is no longer rendered with FreeMarker; anything else still is.
  With `validate-before-dispatch` on, a `TemplateValidationException` is thrown to the caller before the idempotency claim, the rate limit and the audit (REST 400), like a `TemplateRenderException` (422); with it off, both are a `FAILED` response.
- **New delivery statuses** `SENT` and `READ` can reach your listeners and delivery event store from the Meta webhook.
  `READ` can arrive before `DELIVERED`: a listener that projects a current status must never move it backwards.

## API additions and compatibility

Every change to the public API is additive: no type, method or constructor was removed or changed its signature.
Three additions can still break source code:

- **`DeliveryStatus.SENT` and `DeliveryStatus.READ`** are appended after `UNKNOWN` (the ordinals of the earlier constants do not change).
  A `switch` expression or pattern `switch` over `DeliveryStatus` without a `default` no longer compiles, and code compiled against 1.2 fails at runtime when it meets a new constant.
  Add the cases, or a `default`.
- **`RenderedContent`** gains a fifth component, `Object payload`.
  The 4-argument constructor and the `text`, `email` and `emailHtml` factories are kept, so constructor calls compile and behave as before.
  A record pattern that deconstructs `RenderedContent(...)` with 4 components no longer compiles; add the component or use the accessors.
- **`NotificationServiceExtensions`** (1.3.0) gains a sixth component, `List<ChannelContentRenderer> renderers`.
  The 1.3.0 5-argument constructor, `defaults()` and `of(...)` are kept and mean "no renderers"; a 5-component record pattern no longer compiles.

Other additions:

- `com.lazydevs.notification.api.webhook`: `WebhookHandler`, `WebhookRequest`, `WebhookResponse` (DD-36).
- `com.lazydevs.notification.core.webhook.WebhookDispatcher`, always registered and inert without handlers.
- `com.lazydevs.notification.core.template.ChannelContentRenderer`, passed to `DefaultNotificationService` through `NotificationServiceExtensions.renderers` (or `withRenderers(List)`); the auto-configuration collects every renderer bean.
  Two renderers for one channel fail the startup, naming the channel and both classes.
- `RenderedContent.payload(Class)` returns the payload when it has that type.
- `TemplateValidationException` (`TEMPLATE_VALIDATION_FAILED`, HTTP 400).
- `TwilioWebhookHandler` and `SesWebhookHandler` in `notification-rest`; `WebhookController` now takes a `WebhookDispatcher`.
- `BuiltInProviders` lists `WHATSAPP:meta` as implemented.

## Upgrade notes

- **Roll every node to 1.4.0 before it receives WhatsApp callbacks, or keep readers tolerant.**
  The Redis and JDBC stores (since 1.1.2) read an unknown `DeliveryStatus` as `UNKNOWN`; a store that keeps the status as a string needs no migration.
- **Review `switch` statements over `DeliveryStatus`** as described above.
- **Templates are approved at Meta, not in this library.**
  A binding whose template is not approved (or is paused) fails with `TEMPLATE_REJECTED`.
- **Idempotency after a Meta failure.**
  With `notification.idempotency.failure-policy: RELEASE_TRANSIENT` (1.3.0), throttling, connect failures and Meta outages release the idempotency key, and an `AMBIGUOUS` `NO_CONFIRMATION` keeps it.
  A deployment that also wants a retry under the same key after fixing a rejected access token declares its own `IdempotencyReleasePolicy` bean that releases errorCode `CREDENTIAL_REJECTED`; see the [Meta README](../notification-channels/notification-channel-whatsapp/whatsapp-provider-meta/README.md#idempotency-after-a-credential-error).
- **Pin and test the Graph API version.**
  `api-version` defaults to `v25.0`; a later release of this library may move the default, so set it explicitly if you want to decide when to move.

## Configuration to set

**Meta, per tenant**, under `notification.tenants.<tenant>.channels.whatsapp.providers.meta.properties`:

| Key | Default | Description |
|-----|---------|-------------|
| `phone-number-id` | - | Required without a `MetaCredentialsProvider` bean. The sender's phone number id, `<phone-number-id>` |
| `access-token` | - | Required without a `MetaCredentialsProvider` bean |
| `waba-id` | - | Optional WhatsApp Business Account id |
| `api-version` | `v25.0` | Graph API version |
| `base-url` | `https://graph.facebook.com` | https, or http to a loopback address (tests) |
| `timeout` | `10s` | Per request |
| `pair-rate-limit-retry-after` | `6s` | Least retry delay after error 131056 |

**Template bindings**, under `notification.whatsapp.templates.<tenant>.<templateId>`: `name`, `language`, `header`, `body`, `buttons[].index`, `buttons[].sub-type`, `buttons[].variable`.
`<tenant>` may be `default` for every tenant.

**Meta webhook**, under `notification.webhooks.meta`:

| Key | Default | Description |
|-----|---------|-------------|
| `enabled` | `false` | Registers the handler |
| `app-secret` | - | Required while `signature-verification` is on, `your-app-secret` |
| `verify-token` | - | Your own value, entered in the Meta app; without it the handshake is refused |
| `signature-verification` | `true` | Keep on in production |
| `id` | `meta` | Path segment; one handler per Meta app |

The REST route also needs `notification.rest.enabled` and `notification.webhooks.enabled`; with your own endpoint, only `notification.webhooks.meta.enabled`.

**Retries for WhatsApp.**
Throttling and pair-rate-limit answers carry Retry-After hints, which also reach the caller as `NotificationResponse.retryAfter` (1.3.0); set `notification.retry.enabled: true`, and per channel `notification.retry.by-channel.whatsapp.*` if WhatsApp needs other limits than your other channels.
