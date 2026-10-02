# push-provider-fcm

Push provider for [Firebase Cloud Messaging (FCM)](https://firebase.google.com/docs/cloud-messaging) over the [HTTP v1 API](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages/send).
Provider name `fcm` on the `PUSH` channel.
Available since 1.2.0.

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>push-provider-fcm</artifactId>
    <version>${notification-service.version}</version>
</dependency>

<!-- Only when authenticating with credentials: adc or external-account:<path> -->
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>push-provider-fcm-google-auth</artifactId>
    <version>${notification-service.version}</version>
</dependency>
```

The module adds no dependency beyond `notification-api`.
It brings no HTTP library and no Google library: the HTTP v1 calls go through `java.net.http`, the service-account assertion is signed with the JDK, and JSON is built with the Jackson 2 tree model that `notification-api` already depends on.

## Configuration

Like every provider in this library, FCM is configured per tenant.
`ProviderRegistry` creates one provider instance per tenant, calls `configure(properties)` with the tenant's provider properties merged over the channel-level `config`, then `init()`.
There is no global `notification.push.fcm.*` namespace.

```yaml
notification:
  tenants:
    default:
      channels:
        push:
          enabled: true
          providers:
            fcm:
              properties:
                credentials: /config/firebase-service-account.json
                # project-id: my-firebase-project   # defaults to project_id of the key
                android:
                  priority: high
                apns:
                  headers:
                    apns-priority: "10"
```

The provider name `fcm` is enough on its own: the module registers the bean `fcmPushProvider`, which the provider catalog refers to.

| Key | Default | Description |
|-----|---------|-------------|
| `credentials` | - | Required. A service-account JSON file path (optionally `file:`-prefixed), the inline JSON (a value starting with `{`), `adc`, or `external-account:<path>`. See [Credentials](#credentials). |
| `credentials-path` | - | Deprecated alias of `credentials`; logs a warning. Setting both fails. |
| `project-id` | `project_id` of the key | Firebase project id. Required when the credentials do not name a project. |
| `validate-only` | `false` | Send with `validate_only: true`: FCM validates the message and delivers nothing. The result is a success with FCM's placeholder name and `fcm.validateOnly=true` in the metadata. |
| `dry-run` | `false` | Make no HTTP call at all, not even the token call. The message is still mapped and validated; the result is a success with id `dry-run:<uuid>` and `fcm.dryRun=true` in the metadata. |
| `timeout` | `10s` | Per HTTP request, token calls included. Also how long a send waits for a free concurrency slot. Durations accept `500ms`, `10s`, `5m`, `1h` or ISO-8601 (`PT10S`). |
| `timeout-classification` | `ambiguous` | How a failure after the request was sent (a timeout or a lost connection) is classified: `ambiguous` (not retried by default) or `transient` (retried; a duplicate notification is acceptable for this tenant). |
| `concurrency` | `8` | In-flight HTTP v1 calls of this tenant's provider instance. |
| `multi-token-policy` | `all` | When a `deviceTokens` send counts as a success: `all` tokens, or `any` token. See [Several device tokens](#several-device-tokens). |
| `max-tokens` | `500` | Largest `deviceTokens` list accepted. A longer list fails with `FCM_TOO_MANY_TOKENS`. |
| `log-token-hash` | `true` | Log the target as `sha256:<16 hex>`. `false` logs `(hidden)` instead. The raw token or installation id is never logged. |
| `token-refresh-margin` | `5m` | Refresh the access token this long before it expires (at most `30m`). |
| `endpoint` | `https://fcm.googleapis.com` | FCM base URI. https only; plain http is accepted for a loopback address (tests). |
| `token-endpoint` | Google's | OAuth token URI override, same rule. See the note on `token_uri` below. |
| `http-transport` | - | Name of an `FcmHttpTransport` bean for this tenant. Needs the Spring bean `fcmPushProvider`, not `fqcn`. |
| `android.*`, `apns.*`, `webpush.*` | - | Tenant defaults for the platform blocks of every message, as nested maps or dotted keys (`android.priority: high`). Header values become strings. `android.ttl` and `android.priority` are validated. |

Configuration errors fail at startup with a `ProviderConfigurationException` that names the key.
`configure()` reads and checks the key file, but neither `configure()` nor `init()` calls the network: the first access token is fetched by the first send.

## Credentials

Pick one of these.

1. **Service-account JSON key, as a file.**
   Set `credentials` to its path.
   Create the key in the Google Cloud console for a service account that may send with the Firebase Cloud Messaging API (for example the role `Firebase Cloud Messaging API Admin`).
2. **Service-account JSON key, inline.**
   Set `credentials` to the JSON itself, for example from a secret: `credentials: ${FCM_SERVICE_ACCOUNT_JSON}`.
   `FcmSettings.toString()`, log lines and health details show it only as `service-account-json (inline)`.
3. **Application Default Credentials** (`credentials: adc`) or **workload identity federation** (`credentials: external-account:/path/to/config.json`).
   Both need the adapter module `com.github.ifrugal:push-provider-fcm-google-auth`, which brings the Google auth library; see its [README](../push-provider-fcm-google-auth/README.md).
   Without it, `configure()` fails with a message that names the artifact.

The built-in service-account support signs a JWT with the key (RS256, `kid` = `private_key_id`, `iss` = `client_email`, scope `https://www.googleapis.com/auth/firebase.messaging`, audience `https://oauth2.googleapis.com/token`, one hour lifetime) and exchanges it with the OAuth 2.0 JWT bearer grant.
The access token is cached until `token-refresh-margin` before it expires; concurrent sends that find it stale wait for a single refresh.

The key's `token_uri` is ignored unless it is one of Google's own token endpoints, and a warning is logged.
A modified key file must not be able to send the signed assertion elsewhere; set `token-endpoint` to use another endpoint deliberately.

Token failures fail the send with error code `FCM_AUTH_FAILED`.
`invalid_grant`, `invalid_client` and every other OAuth error in a 4xx answer are `PERMANENT` (a deleted or disabled key, a wrong clock).
408, 429, 5xx and I/O errors at the token endpoint are `TRANSIENT`.

A custom credential source plugs in as an `FcmAccessTokenProviderFactory`: a Spring bean (asked first), or a `META-INF/services/com.lazydevs.notification.channel.push.fcm.FcmAccessTokenProviderFactory` entry.
The first factory whose `supports(credentials)` is true creates the tenant's `FcmAccessTokenProvider`.

## Registration

`FcmPushProviderAutoConfiguration` registers the bean `fcmPushProvider` whenever this jar is on the classpath; it has no class condition, because the module needs nothing else.
The bean is a prototype, so every tenant gets its own configured instance.
A bean that you declare under the same name replaces it.
The class `com.lazydevs.notification.channel.push.fcm.FcmPushProvider` also has a public no-arg constructor for `fqcn:` use; it finds transports and token provider factories with `ServiceLoader`.

## HTTP client

The default transport is `JdkFcmHttpTransport.shared()`: one HTTP/2 `java.net.http.HttpClient` for the whole JVM, so every tenant multiplexes its sends over the same connections.
It uses the JVM's proxy selector and TLS settings and follows no redirects.

Replace it with an `FcmHttpTransport` bean, for example to route through a proxy, to add client metrics or to use another HTTP library.
The auto-configured provider uses the bean when it is the only one (or the primary one); with several, each tenant can pick one by name with `http-transport`.
A transport must be thread-safe and must not retry; it reports a missing answer as an `FcmTransportException` and says through `requestSent()` whether the request may have reached FCM.

## Targets

Set exactly one of these on the `PushRecipient`; anything else fails with `FCM_INVALID_TARGET_SPEC` before FCM is called.

| `PushRecipient` field | FCM target |
|-----------------------|------------|
| `deviceToken` | `message.token` |
| `deviceTokens` | one `message.token` call per token, see [Several device tokens](#several-device-tokens) |
| `fid` | `message.fid` (Firebase installation id) |
| `topic` | `message.topic`; a `/topics/` prefix is removed |
| `condition` | `message.condition` |

## Message mapping

| Library field | FCM field |
|---------------|-----------|
| rendered subject, else `PushRecipient.title` | `notification.title` |
| rendered text, else `PushRecipient.body` | `notification.body` |
| `imageUrl` | `notification.image` |
| `data` | `data` (string values) |
| `badge` | `apns.payload.aps.badge` |
| `sound` | `apns.payload.aps.sound` and `android.notification.sound` |
| `clickAction` | `android.notification.click_action`, `apns.payload.aps.category`, and `webpush.fcm_options.link` when it is an https URL |

For the `PUSH` channel the template engine renders plain text, so the rendered text becomes the body and `PushRecipient.title` the title.

Data keys that FCM reserves (`from`, `message_type`, `google.*`, `gcm.*`) fail with `FCM_INVALID_DATA_KEY`.

**Size limits.**
The UTF-8 bytes of every data key and value plus the notification title, body and image may not exceed 4096 bytes, or 2048 bytes for a topic or condition.
A larger message fails with `FCM_PAYLOAD_TOO_LARGE` and FCM is not called.
FCM's own check remains authoritative and is reported as `INVALID_ARGUMENT`.

**Platform overrides.**
The `android`, `apns` and `webpush` blocks start from the tenant defaults (`android.*`, `apns.*`, `webpush.*` keys), then the fields above, then these per-request `NotificationRequest.metadata` keys are merged over them:

| Metadata key | FCM field | Accepted values |
|--------------|-----------|-----------------|
| `fcm.android.priority` | `android.priority` | `normal`, `high` |
| `fcm.android.ttl` | `android.ttl` | seconds with an `s` suffix, such as `3600s` or `3.5s`, at most `2419200s` |
| `fcm.android.collapse_key` | `android.collapse_key` | any text |
| `fcm.apns.headers.apns-priority` | `apns.headers.apns-priority` | `10`, `5`, `1` |
| `fcm.apns.headers.apns-expiration` | `apns.headers.apns-expiration` | UNIX epoch seconds, or `0` |
| `fcm.apns.headers.apns-collapse-id` | `apns.headers.apns-collapse-id` | at most 64 bytes |
| `fcm.webpush.headers.TTL` | `webpush.headers.TTL` | whole seconds |

Metadata keys without the `fcm.` prefix are ignored, so audit metadata does no harm.
Any other `fcm.*` key, or an invalid value, fails with `FCM_INVALID_OVERRIDE` so a typo does not go unnoticed.

## Several device tokens

The HTTP v1 API has no multicast, so `deviceTokens` sends one call per token, in parallel on virtual threads, within the tenant's `concurrency`.
Duplicate tokens are sent once.
The provider metadata of the result carries per-token results, identified by hash:

```json
{
  "fcm.results": [
    {"tokenHash": "sha256:0f1e2d3c4b5a6978", "status": "SENT", "name": "projects/my-project/messages/0:1700000000000000%abc"},
    {"tokenHash": "sha256:8796a5b4c3d2e1f0", "status": "FAILED", "errorCode": "UNREGISTERED"}
  ],
  "fcm.successCount": 1,
  "fcm.failureCount": 1
}
```

| Outcome | `multi-token-policy: all` | `multi-token-policy: any` |
|---------|---------------------------|---------------------------|
| every token sent | success | success |
| some sent, some failed | `AMBIGUOUS`, `FCM_PARTIAL_FAILURE` | success |
| none sent, every failure transient | `TRANSIENT` | `TRANSIENT` |
| none sent, a failure may have been accepted | `AMBIGUOUS` | `AMBIGUOUS` |
| none sent, otherwise | `PERMANENT` | `PERMANENT` |

A partial result is `AMBIGUOUS` under `all` because a retry would notify the devices that already got the message a second time.
The message id is the name of the first sent token, or `fcm-local:<uuid>` when none was sent.
A send waits at most `timeout` for a concurrency slot, so a long list needs `concurrency` high enough to work through it: a token that got no slot fails with `FCM_CONCURRENCY_LIMIT` (`TRANSIENT`).

## Failure classification and retries

FCM errors are `google.rpc.Status` bodies with an `FcmError` detail (`errorCode`) and, for bad requests, `BadRequest.fieldViolations`.

| FCM answer | Classification |
|------------|----------------|
| 400 `INVALID_ARGUMENT` on `message.token` or `message.fid` | `PERMANENT`, invalid-target event |
| 400 otherwise | `PERMANENT` |
| 404 `UNREGISTERED` | `PERMANENT`, invalid-target event |
| 403 `SENDER_ID_MISMATCH` | `PERMANENT`, invalid-target event |
| 403 without an FcmError (missing IAM permission, API disabled) | `PERMANENT` |
| 429 `QUOTA_EXCEEDED`, or 429 without an FcmError | `TRANSIENT`, `Retry-After`, else 60 seconds |
| 503 `UNAVAILABLE`, 500 `INTERNAL`, any 5xx | `TRANSIENT`, `Retry-After` when present |
| 401 `THIRD_PARTY_AUTH_ERROR` (APNs or web push credentials in the Firebase console) | `PERMANENT` |
| 401 without an FcmError (access token rejected) | the token is invalidated and the call resent once; if that fails too, `TRANSIENT` |
| `UNSPECIFIED_ERROR`, or a 4xx without a `google.rpc.Status` body | `UNKNOWN` |
| no connection (refused, DNS, TLS handshake, connect timeout) | `TRANSIENT`, `FCM_CONNECT_FAILED` |
| timeout or lost connection after the request was sent | `AMBIGUOUS` (`FCM_TIMEOUT`, `FCM_CONNECTION_LOST`), or `TRANSIENT` with `timeout-classification: transient` |

`SendResult.errorCode` is the FCM `errorCode` (for example `UNREGISTERED`), else the `google.rpc.Status` status, else `HTTP_<status>`, or one of the module's `FCM_*` codes.
FCM's error text is copied to `SendResult.errorMessage` with the target replaced by its hash.

The `Retry-After` header (seconds or an HTTP-date) is passed to the library's `RetryExecutor` as the retry hint.
FCM asks for at least one minute after a quota error, so a 429 without the header carries a 60 second hint.
The executor does not cut a hint short: a hint longer than `notification.retry.max-retry-after` (by default `max-delay`, 30 seconds) stops the retries, and the failure goes to the dead-letter store when one is configured.
To let push sends wait out a quota minute, raise it for the channel, for example `notification.retry.by-channel.push.max-retry-after: 70s`.
The executor waits on the calling thread, so do that only where a minute-long send is acceptable, such as Kafka-driven sends.

## Invalid tokens and delivery events

A device token or installation id that FCM rejects as gone (`UNREGISTERED`), foreign (`SENDER_ID_MISMATCH`) or malformed (`INVALID_ARGUMENT` on the token) is published as a `DeliveryEvent` to the registered `DeliveryEventListener`s and the delivery-event store (DD-25):

| Field | Value |
|-------|-------|
| `status` | `BOUNCED` |
| `reason` | `INVALID_TARGET` (`DeliveryEvents.REASON_INVALID_TARGET`) |
| `providerName` | `fcm` |
| `providerMessageId` | the `SendResult.messageId` of the send, also the response's `providerMessageId` |
| `providerEventId` | SHA-256 hex of `messageId|tokenHash|BOUNCED` |
| `attributes.failure` | the FCM error code, for example `UNREGISTERED` |
| `attributes.errorCode` | the `errorCode` of the send result (the FCM code for a single target) |
| `attributes.targetType` | `token` or `fid` |
| `attributes.tokenHash` | `sha256:` and the first 16 hex characters of the SHA-256 of the token |

The event is published on the thread that called `send`, after every token of a `deviceTokens` send has finished, so a listener can read thread-bound state such as the tenant.
It never carries the token itself.
Topic and condition targets produce no event.

To delete dead tokens, hash your stored tokens the same way and remove the match:

```java
@Component
class DeadPushTokenListener implements DeliveryEventListener {

    private final DeviceTokenRepository tokens;

    DeadPushTokenListener(DeviceTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Override
    public void onEvent(DeliveryEvent event) {
        if ("fcm".equals(event.providerName())
                && DeliveryEvents.REASON_INVALID_TARGET.equals(event.reason())) {
            tokens.deleteByHash(event.attributes().get(DeliveryEvents.ATTR_TOKEN_HASH));
        }
    }

    /** Store this next to each token: "sha256:" + the first 16 hex characters of SHA-256(token). */
    static String hash(String token) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        return "sha256:" + HexFormat.of().formatHex(digest).substring(0, 16);
    }
}
```

FCM has no per-message delivery webhook, so these send-time events are the only delivery signal the provider produces.

## Message ids

| Outcome | `SendResult.messageId` |
|---------|------------------------|
| success | FCM's message name, `projects/<project>/messages/<id>` |
| failure | `fcm-local:<uuid>`, also the `providerMessageId` of the events published for it |
| `dry-run` | `dry-run:<uuid>` |

## Quotas and concurrency

- FCM's default quota is 600,000 messages per minute per project, measured in messages, not requests, over minutes that are not aligned to the clock.
- Client errors (HTTP 400 to 499, except 429) count against the quota, so remove dead tokens instead of sending to them again.
- Android devices accept up to 240 messages per minute and 5,000 per hour each.
  Collapsible messages are limited to a burst of 20 per device, with a refill of one every three minutes.
  iOS follows the APNs limits.
- Topic sends are limited separately, and the payload limit for a topic is 2048 bytes.
- The default `concurrency` of 8 suits most tenants.
  As a rule of thumb, `concurrency` is the target messages per second times the typical request latency in seconds: 200 messages per second at 50 ms needs 10.
  Keep the sum over all pods and tenants of one Firebase project well inside the project quota, and pair it with a library rate-limit rule for the push channel.

See the FCM guides on [throttling and quotas](https://firebase.google.com/docs/cloud-messaging/throttling-and-quotas) and [sending at scale](https://firebase.google.com/docs/cloud-messaging/scale-fcm).

## Logging and secrets

The private key, the signed assertion and the access token are never logged and never appear in a `SendResult`.
Targets appear only as `sha256:<16 hex>` (or `(hidden)` with `log-token-hash: false`); topics are logged by name and conditions as `(set)`.
Per-token failures of a `deviceTokens` send are logged at DEBUG, with one WARN line for the whole send.

## Testing your integration

`FcmPushProvider.withTransport(settings, transport, tokenProvider)` builds a ready provider over your own `FcmHttpTransport` and `FcmAccessTokenProvider`, so a test exercises the real message mapping, classification, per-token results and events without Google.
`configure(...)` and `init()` are no-ops on such an instance.

```java
FcmHttpTransport fakeFcm = request -> new FcmHttpResponse(200, Map.of(),
        "{\"name\":\"projects/my-project/messages/1\"}".getBytes(StandardCharsets.UTF_8));
FcmAccessTokenProvider staticToken = new FcmAccessTokenProvider() {
    public FcmAccessToken token() { return new FcmAccessToken("test-token", Instant.now().plusSeconds(3600)); }
    public void invalidate() { }
    public Optional<String> projectId() { return Optional.empty(); }
    public void close() { }
};

FcmPushProvider provider = FcmPushProvider.withTransport(
        FcmSettings.fromMap(Map.of("project-id", "my-project")), fakeFcm, staticToken);

SendResult result = provider.send(request, RenderedContent.text("Your order shipped"));
```

To test the whole path, service-account flow included, point `endpoint` and `token-endpoint` at a loopback stub (`http://127.0.0.1:<port>`) and give the tenant a service-account JSON for a key pair your test generates.
The module's own tests do exactly that with a `com.sun.net.httpserver` stub that verifies the assertion's signature and claims.
`validate-only: true` checks messages against the real FCM without delivering them; `dry-run: true` makes no call at all.

## GraalVM native image

The module binds no JSON to classes and uses no reflection of its own, so it needs no reachability metadata.
`ServiceLoader` lookups of `FcmAccessTokenProviderFactory` and `FcmHttpTransport` are resolved by the native-image builder from the `META-INF/services` files on the classpath; the built-in service-account factory is also used directly when no entry is found.
Native support is not yet claimed or tested in CI.
Smoke plan for a native build of your application:

1. Build with `spring-boot:process-aot` and `native:compile` with this module and a tenant configured with `credentials` pointing at a service-account file.
2. Start the binary and check that startup logs `FCM push provider initialized` without a network call.
3. Send with `validate-only: true` to a real token and expect a success with FCM's placeholder name.
4. Send to a deleted token and expect `UNREGISTERED` with a `BOUNCED` event.
5. Repeat step 3 with the google-auth adapter and `credentials: adc` if you use it; the Google auth library may need its own metadata.
