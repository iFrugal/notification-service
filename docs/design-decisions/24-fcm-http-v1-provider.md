# Decision 24: FCM HTTP v1 Push Provider

## Status: DECIDED

## Context

The `PUSH` channel had an SPI (`PushProvider`) and a catalog entry for `fcm`, but no implementation.
The artifactId `push-provider-fcm` existed on Maven Central for 1.0.x only as an empty jar.

Firebase Cloud Messaging is the cross-platform push service for Android, iOS (through APNs) and web push.
Its current API is HTTP v1 (`POST /v1/projects/{project}/messages:send`), authenticated with OAuth 2.0 access tokens for a service account; the legacy HTTP and XMPP APIs are deprecated.
HTTP v1 has no multicast: one call sends to one token, topic or condition.
It reports invalid device tokens in the send response (`UNREGISTERED`, `SENDER_ID_MISMATCH`), and has no per-message delivery webhook.

The obvious implementation, the Firebase Admin SDK, brings Google's HTTP client, gRPC-era auth libraries, Guava and its own retry and threading model.
The library's other providers already showed what a heavy SDK costs: the ACS SDK pinned a Netty that clashes with Spring Boot 4.1.

## Decision

### Module and dependencies

A new module `push-provider-fcm` under a restored aggregator `notification-channels/notification-channel-push`, package `com.lazydevs.notification.channel.push.fcm`.
Its only dependency is `notification-api`; `spring-boot-autoconfigure` is optional and only used by the auto-configuration.

- HTTP goes through the module's own SPI, `FcmHttpTransport` (`FcmHttpRequest` in, `FcmHttpResponse` out, `FcmTransportException` with `requestSent()` when no answer came).
  The default is `JdkFcmHttpTransport.shared()`, one HTTP/2 `java.net.http` client per JVM.
  An application replaces it with a bean, a `META-INF/services` entry, or per tenant with `http-transport`.
- JSON goes through the Jackson 2 tree model that `notification-api` already brings; nothing is bound to classes, so the module needs no native-image hints.
- Authentication goes through a second SPI, `FcmAccessTokenProvider` created by an `FcmAccessTokenProviderFactory` chosen by the tenant's `credentials` value.
  The built-in `ServiceAccountJwtTokenProvider` signs an RS256 JWT with the service-account key and exchanges it with the JWT bearer grant (RFC 7523), caching the token until a refresh margin before expiry, with single-flight refresh.
  `adc` and `external-account:<path>` need the optional adapter module `push-provider-fcm-google-auth`, which wraps the Google auth library; without it `configure()` fails naming the artifact.
- The key's `token_uri` is used only when it is one of Google's own endpoints, so a modified key file cannot redirect the signed assertion; `token-endpoint` overrides it deliberately.

### Provider lifecycle

`FcmPushProvider` implements `PushProvider` and `DeliveryEventEmitter`.
`configure()` parses `FcmSettings`, picks the transport, creates the token provider (reading and checking the key) and resolves the project id, failing fast with a `ProviderConfigurationException`.
Neither `configure()` nor `init()` calls the network.
It is reachable three ways, like the ACS provider: the prototype bean `fcmPushProvider`, the no-arg constructor for `fqcn`, and the test seam `withTransport(settings, transport, tokenProvider)`.

### Mapping

Exactly one target (`deviceToken`, `deviceTokens`, `fid`, `topic`, `condition`), else `PERMANENT FCM_INVALID_TARGET_SPEC`.
The rendered subject and text win over `PushRecipient.title` and `body`.
`badge`, `sound` and `clickAction` map to their APNs, Android and web push fields.
Per-request platform settings come only from a whitelist of `NotificationRequest.metadata` keys (`fcm.android.priority`, `fcm.android.ttl`, `fcm.android.collapse_key`, `fcm.apns.headers.apns-priority`, `fcm.apns.headers.apns-expiration`, `fcm.apns.headers.apns-collapse-id`, `fcm.webpush.headers.TTL`); tenants set defaults with `android.*`, `apns.*` and `webpush.*`.
Reserved data keys and payloads over 4096 bytes (2048 for topics and conditions) fail `PERMANENT` before FCM is called.

### Classification

| FCM answer | Classification |
|------------|----------------|
| 400 `INVALID_ARGUMENT` on `message.token` | `PERMANENT`, invalid-target event |
| 400 otherwise | `PERMANENT` |
| 404 `UNREGISTERED`, 403 `SENDER_ID_MISMATCH` | `PERMANENT`, invalid-target event |
| 429 `QUOTA_EXCEEDED` | `TRANSIENT`, `Retry-After`, else 60 seconds |
| 503, 500 | `TRANSIENT`, `Retry-After` when present |
| 401 `THIRD_PARTY_AUTH_ERROR` | `PERMANENT` |
| 401 without an FcmError | invalidate the token, resend once inline, then `TRANSIENT` |
| `UNSPECIFIED_ERROR`, unparsable 4xx | `UNKNOWN` |
| unparsable 5xx, or no connection (`requestSent=false`) | `TRANSIENT` |
| timeout after the request was sent | `AMBIGUOUS`, or `TRANSIENT` with `timeout-classification: transient` |

### Several tokens

`deviceTokens` sends one call per token on virtual threads, bounded by a per-instance (per-tenant) semaphore of `concurrency` permits that single sends also take; no permit within `timeout` is `TRANSIENT FCM_CONCURRENCY_LIMIT`.
Per-token results go into the provider metadata (`fcm.results`, `fcm.successCount`, `fcm.failureCount`).
With `multi-token-policy: all` a partial result is `AMBIGUOUS`, with `any` one sent token is a success; when none was sent the result is `TRANSIENT` if every failure was transient, `AMBIGUOUS` if one may have been accepted, else `PERMANENT`.

### Ids and events

The message id is FCM's message name on success and `fcm-local:<uuid>` on failure, so a failed attempt still has a join key.
An invalid device token or installation id is published through the injected `DeliveryEventPublisher` (DD-25) as `BOUNCED` with reason `INVALID_TARGET`, attributes `failure`, `errorCode`, `targetType` and `tokenHash`, and `providerEventId = sha256(messageId|tokenHash|status)`.
Events are published on the calling thread after every token has finished.
A target appears only as `sha256:<16 hex>`, in logs, results and events.

## Reasoning

### Why not the Firebase Admin SDK

The SDK would be the only reason for a large transitive tree (Google HTTP client, auth library, Guava) in an application that wants to send push notifications.
It also retries on its own and runs its own thread pool, which would duplicate the library's `RetryExecutor` and hide the classification the retry predicate needs.
The HTTP v1 API is small and stable: one endpoint, one JSON shape, one error format.
The cost of owning it is one module of plain Java, and in exchange there is no dependency to keep in step with Spring Boot.

### Why a transport SPI rather than a fixed client

Proxies, mutual TLS, client metrics and corporate HTTP stacks differ per deployment.
A one-method SPI lets an application supply what it already uses, and lets tests inject a stub without a mocking library.
The JDK client is the default because it pins nothing outside the JDK.

### Why the service-account flow is built in and ADC is an adapter

A service-account key covers most deployments and needs only the JDK's RSA signature.
Application Default Credentials and workload identity federation involve metadata servers, STS token exchange and several credential file formats; reimplementing them would be a security liability, so they come from Google's own library in an optional module.

### Why `AMBIGUOUS` for timeouts

FCM has no idempotency key.
A request that timed out after it was sent may have been delivered, and a retry would notify the user twice.
Tenants for which a duplicate is harmless opt out with `timeout-classification: transient`.

### Why one call per token in parallel

HTTP v1 has no multicast, and the Admin SDK itself fans out with one call per token over HTTP/2.
Virtual threads keep the code sequential per token, and the semaphore keeps one tenant from exhausting the project's quota or the connection.

## Consequences

### Positive

- Push works with one dependency and no Google library.
- Dead tokens reach the application through the existing listener and store seams, keyed by a stable hash.
- Every FCM failure is classified for the retry executor, including Retry-After hints.

### Negative

- The module owns the HTTP v1 mapping and must follow FCM changes (for example the move from `token` to `fid`).
- A large `deviceTokens` list holds the calling thread until every token is done, and needs `concurrency` sized for it.
- The 60 second quota hint is longer than the default `max-delay`, so a quota error stops the retries unless the push channel raises `max-retry-after`.

## Related Decisions

- [05-provider-registration.md](./05-provider-registration.md) - `beanName`, `fqcn` and built-in provider resolution.
- [13-retries-and-dlq.md](./13-retries-and-dlq.md) - failure classification and the retry executor.
- [16-webhook-delivery-callbacks.md](./16-webhook-delivery-callbacks.md) - why FCM has no webhook handler.
- [25-ambiguous-failures-retry-after-provider-events.md](./25-ambiguous-failures-retry-after-provider-events.md) - `AMBIGUOUS`, the Retry-After cap and provider-originated events, designed together with this provider.
