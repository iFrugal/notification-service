# Decision 36: Webhook Handler SPI

## Status: DECIDED

## Context

DD-16 introduced provider delivery callbacks with one hardcoded `WebhookController` in `notification-rest`: Twilio and SES were controller methods, bound to the servlet API, and the controller only existed with `notification.rest.enabled=true`.

- A new provider (Meta WhatsApp, DD-35) could not add its callback without editing the controller.
- An application that embeds the library without its REST module could not ingest callbacks at all, although it may well have its own HTTP layer.
- Signature checks need the raw body bytes, which the controller methods received in provider-specific shapes (`@RequestParam` maps, a `String`).

## Decision

### SPI in `notification-api`, servlet-free

```java
public interface WebhookHandler {
    String id();                                 // first path segment, e.g. "twilio", "meta"
    WebhookResponse handle(WebhookRequest request);
}

public record WebhookRequest(String method, String subPath,
        Map<String, List<String>> headers,       // case-insensitive lookup
        Map<String, List<String>> queryParameters,
        byte[] body,                             // raw bytes, for signatures
        String requestUrl) { ... }               // full URL, for schemes that sign it

public record WebhookResponse(int status, String contentType, String body,
        List<DeliveryEvent> events, boolean signatureRejected) { ... }
```

- A handler verifies the provider's signature over the raw body before trusting anything, and answers a failure with `WebhookResponse.signatureRejected(...)` (HTTP 403).
- It parses the callback into `events`; it does not call listeners itself.
- It should not throw: bad input is a 4xx answer.

### `WebhookDispatcher` in `notification-core`

- Finds the handler by id: an unknown id is 404, so a URL registered with a provider that nothing serves is loud.
- Calls it: an exception is logged and answered 500.
- Counts a signature rejection in `notification.webhook.signature.failed.total{provider=<id>}` and every event in `notification.delivery-events.received.total{provider, status}` (DD-22).
- Hands every event to every `DeliveryEventListener` (including a `DeliveryEventStore`, DD-17) on the calling thread; a listener that throws is logged and skipped, and the provider still gets the handler's answer.
- Is always registered and inert without handlers; duplicate or malformed ids fail the startup.

### REST route

`WebhookController` becomes one route, `GET|POST ${notification.rest.base-path}${notification.webhooks.base-path}/{handlerId}/**`, that builds a `WebhookRequest` and returns the dispatcher's answer.
It still requires `notification.rest.enabled=true` and `notification.webhooks.enabled=true`.

Twilio and SES move into `TwilioWebhookHandler` (`/twilio/status`) and `SesWebhookHandler` (`/ses/sns`), registered with the controller.
Paths, properties, status codes and response bodies are unchanged; a wrong method, path or content type is still answered 405, 404 or 415.
One correction: the Twilio handler reads the signed parameters from the form body only.
The 1.2 controller's `@RequestParam` map also held query-string parameters, which made a callback URL with a query string fail Twilio's signature scheme (Twilio signs the URL, query included, plus the POST parameters).

### Without the REST module

An application mounts the dispatcher on its own endpoint:

```java
@PostMapping("/webhooks/whatsapp/meta")
ResponseEntity<String> meta(@RequestBody byte[] body, @RequestHeader MultiValueMap<String, String> headers) {
    WebhookResponse answer = dispatcher.dispatch("meta",
            new WebhookRequest("POST", "", headers, Map.of(), body, null));
    return ResponseEntity.status(answer.status()).body(answer.body());
}
```

The handler beans come from the provider modules (`notification.webhooks.meta.enabled=true` for Meta), independent of `notification.rest.enabled`.

## Out of scope

- **Tenant resolution for webhook events.** Callbacks carry no tenant; an application that stores events per tenant correlates on the provider message id (or the request id that Meta echoes in `biz_opaque_callback_data`).
- **Inbound messages** (replies, button clicks). Handlers may count and ignore them; a separate SPI is a later decision.
- **Async processing.** Listeners run on the request thread, as in DD-16.
- **Moving Twilio and SES to their provider modules.** They stay in `notification-rest` with their existing properties, so upgrading changes nothing for their users.

## Reasoning

### Why servlet-free

Keeping servlet types out of the SPI is what lets an application without the REST module, or on another web stack, reuse every handler.
The cost is one small adapter in the controller.

### Why the dispatcher, not the publisher, calls the listeners

`DeliveryEventPublisher` (DD-25) is the path for events a provider learns while sending, and counts them as `emitted`.
Webhook events keep their own `received` metric and the per-request listener guard of DD-16, so the dispatcher calls the listeners itself.

### Why one generic route

A route per provider would make every new provider a REST module change.
With `/{handlerId}/**`, a provider module adds a bean, and the REST module never changes again.
