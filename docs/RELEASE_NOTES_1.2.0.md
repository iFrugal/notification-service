# notification-service 1.2.0

## Highlights

- **Push notifications with Firebase Cloud Messaging.**
  `push-provider-fcm` sends over the FCM HTTP v1 API to a device token, several device tokens, a Firebase installation id, a topic or a condition, with no HTTP library and no Google library (DD-24).
  A device token that FCM reports as gone is published as a `BOUNCED` delivery event, so a listener can delete it.
- **Application Default Credentials and workload identity federation for FCM.**
  The optional `push-provider-fcm-google-auth` adds `credentials: adc` and `credentials: external-account:<path>` on top of the Google auth library, with every token call going through the application's `FcmHttpTransport`.
- **A failure that may have reached the provider is no longer retried.**
  The new `FailureType.AMBIGUOUS` marks a timeout or lost connection after the request was sent; the default retry predicate does not retry it, so a recipient cannot get the message twice (DD-25).
- **Retry-After hints are bounded.**
  A provider hint longer than `notification.retry.max-retry-after` stops the retries and hands the failure to the dead-letter store instead of holding the caller's thread.
- **Providers can publish delivery events while sending.**
  `DeliveryEventPublisher` and `DeliveryEventEmitter` route events that a provider learns from the send response to the same listeners and store as webhook callbacks.
- **A bill of materials.**
  `notification-service-bom` manages the version of every library jar of this project.
- **The standalone server bundles ACS and FCM.**
  The Docker image now includes the Azure Communication Services Email and FCM providers, and the google-auth adapter.

## New modules

| Coordinates | What it is |
|-------------|------------|
| `com.github.ifrugal:push-provider-fcm:1.2.0` | The FCM provider, `PUSH:fcm` ([README](../notification-channels/notification-channel-push/push-provider-fcm/README.md)) |
| `com.github.ifrugal:push-provider-fcm-google-auth:1.2.0` | `adc` and `external-account:<path>` credentials for FCM ([README](../notification-channels/notification-channel-push/push-provider-fcm-google-auth/README.md)) |
| `com.github.ifrugal:notification-service-bom:1.2.0` | The BOM; import it with `<type>pom</type>` and `<scope>import</scope>` |

The artifactId `push-provider-fcm` existed on Maven Central for versions 1.0.0 to 1.0.2 as an empty jar with no classes.
1.2.0 is its first release with an implementation; do not use the older versions.
The aggregator pom `notification-channel-push` is published again for the same reason.

The BOM manages only this project's artifacts, so Spring Boot, Jackson and the provider SDKs stay under your own dependency management:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.github.ifrugal</groupId>
            <artifactId>notification-service-bom</artifactId>
            <version>1.2.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## Behaviour changes

- **`AMBIGUOUS` is not retried by default.**
  `RetryPredicate.DEFAULT` retries `TRANSIENT`, `UNKNOWN` and a `null` failure type, and no longer anything else.
  An `AMBIGUOUS` failure takes the normal failure path: the caller gets `FAILED`, the audit records it, and the dead-letter store (when enabled) keeps it with `failureType=AMBIGUOUS`, so an operator can check with the provider before replaying.
  `DefaultNotificationService` logs one WARN, "Notification failed, not retried: ambiguous", with the provider message id.
- **Custom retry predicates written as `!= PERMANENT` now retry `AMBIGUOUS`.**
  A predicate such as `result.failureType() != FailureType.PERMANENT` retries the new constant and may send twice.
  List the types to retry instead, as the default does.
- **SES, SMTP and Twilio timeouts after the request was sent are `AMBIGUOUS`.**
  They were `TRANSIENT` and retried.
  SES: `ApiCallTimeoutException`, `ApiCallAttemptTimeoutException` and an `SdkClientException` caused by a read timeout.
  SMTP: a `SocketTimeoutException` in the cause chain.
  Twilio: an `ApiException` without a status caused by a read timeout.
  Connect failures and connect timeouts stay `TRANSIENT`, because nothing was sent.
  ACS is unchanged: a retry reuses the `Operation-Id`, so ACS recognises the resend.
  The AWS SDK still retries a read timeout itself before the SES provider sees it; the SES README explains how to set its attempts to 1.
- **A Retry-After hint above the cap stops the retries.**
  In 1.1.2 the executor waited for the hint, capped at `max-delay`.
  Now a hint at or below `notification.retry.max-retry-after` (default: the rule's `max-delay`, 30 seconds unless you changed it) is waited for as before, and a longer hint stops the retries at once with a WARN; the failure goes to the dead-letter store when one is configured.
  FCM asks for 60 seconds after a quota error, so raise the cap for the push channel if a send may wait that long, for example `notification.retry.by-channel.push.max-retry-after: 70s`.
- **The standalone server bundles ACS and FCM.**
  `notification-server` now contains `email-provider-acs` with `azure-core-http-jdk-httpclient`, `push-provider-fcm` and `push-provider-fcm-google-auth`, about 8.1 MB more in the application jar.
  `azure-identity` is not bundled: in the image, ACS authenticates with `connection-string`.
  The push section of its `application.yml` sets `credentials` from `FCM_CREDENTIALS`, falling back to the old `FCM_CREDENTIALS_PATH` variable, so existing deployments keep working.
- **New meter** `notification.delivery-events.emitted.total{provider,status}` counts the events providers publish while sending.

## API additions and compatibility

Every change to the public API is additive: no type, method or constructor was removed or changed its signature.
Two additions can still break source code:

- **`FailureType.AMBIGUOUS`** is appended after `UNKNOWN`.
  A `switch` expression or pattern `switch` over `FailureType` without a `default` no longer compiles, and code compiled against 1.1 fails at runtime when it meets the new constant (`MatchException` on Java 21 and later).
  Add a case, or a `default`.
- **`PushRecipient`** gains two components at the end: `String fid` (a Firebase installation id) and `List<String> deviceTokens` (several tokens, one send).
  The 11-argument constructor of 1.1 is kept, so constructor calls and JSON are compatible, and the new fields are left out of JSON when unset.
  A record pattern that deconstructs `PushRecipient(...)` with 11 components no longer compiles; add the two components or use the accessors.

Other additions:

- `com.lazydevs.notification.api.delivery.DeliveryEventPublisher` (`publish(DeliveryEvent)`, `NO_OP`) and `DeliveryEventEmitter` (`setDeliveryEventPublisher(DeliveryEventPublisher)`).
  A provider that implements `DeliveryEventEmitter` gets the publisher from `ProviderRegistry` before `configure()`, on every resolution path (bean name, `fqcn`, built-in name).
  It publishes on the thread that runs the send, so listeners can read the tenant.
  Core registers `ListenerDeliveryEventPublisher` under `@ConditionalOnMissingBean`; it calls every `DeliveryEventListener` and isolates a failing one.
- `ProviderRegistry(NotificationProperties, ProviderResolver, DeliveryEventPublisher)`; the two-argument constructor remains and uses `DeliveryEventPublisher.NO_OP`.
- `DeliveryEvents` constants for provider-originated events: `REASON_INVALID_TARGET`, `ATTR_FAILURE`, `ATTR_ERROR_CODE`, `ATTR_TARGET_TYPE`, `ATTR_TOKEN_HASH`, `TARGET_TYPE_TOKEN`, `TARGET_TYPE_FID`.
  There is no new `DeliveryStatus` constant: an invalid device token is `BOUNCED` with reason `INVALID_TARGET`, so stores and listeners need no change.
- `SendResult.withRetryAfter(Duration)` and `SendResult.failure(errorCode, errorMessage, failureType, messageId, metadata)`.
  The record's constructor is unchanged.
- `FailureTypes.fromExceptionAfterSubmit(Throwable)` (connect-phase failures `TRANSIENT`, other I/O failures `AMBIGUOUS`, anything else `UNKNOWN`) and `FailureTypes.parseRetryAfter(String, Clock)` (delta-seconds or an HTTP-date).
  `FailureTypes.fromException(Throwable)` is unchanged.
- `maxRetryAfter` on `NotificationProperties.RetryProperties` and `RetryRule`, and `RetryRule.effectiveMaxRetryAfter()`.
- `PiiMasking.mask(Recipient)` masks a push recipient's `fid` like a device token and shows `deviceTokens` as a count.
- `BuiltInProviders` lists `PUSH:fcm` as implemented.

## Upgrade notes

- **Roll every node to 1.1.2 before 1.2.0.**
  1.1.2 reads an unknown `FailureType` constant as `UNKNOWN` and ignores unknown fields in the Redis and JDBC stores.
  A 1.1.0 or 1.1.1 node that shares a store with 1.2.0 cannot read a dead-letter entry or idempotency record that carries `AMBIGUOUS`, or a stored request whose `PushRecipient` has `fid` or `deviceTokens`.
- **Review custom `RetryPredicate` beans** for the `!= PERMANENT` pattern described above.
- **Template auto-escaping is still off by default.**
  `notification.template.auto-escape` stays `false` in 1.2.0, as in 1.1.2; set it to `true` to escape interpolated values in HTML bodies.
- **The 1.1.2 id rule still applies** if you upgrade from 1.1.0 or 1.1.1: tenant and template ids must match `[A-Za-z0-9][A-Za-z0-9._-]{0,127}` and may not contain `..`, or the request is a 404.
- `credentials-path` of the FCM provider is a deprecated alias of `credentials` and logs a warning; setting both fails.

## Configuration to set

**FCM, per tenant**, under `notification.tenants.<tenant>.channels.push.providers.fcm.properties`:

| Key | Default | Description |
|-----|---------|-------------|
| `credentials` | - | Required. A service-account JSON file path, the inline JSON, `adc`, or `external-account:<path>` |
| `project-id` | `project_id` of the key | Firebase project id; required when the credentials do not name one |
| `validate-only` | `false` | FCM validates the message and delivers nothing |
| `dry-run` | `false` | No HTTP call at all; success with a `dry-run:<uuid>` id |
| `timeout` | `10s` | Per HTTP request, token calls included |
| `timeout-classification` | `ambiguous` | `ambiguous` or `transient`, for a failure after the request was sent |
| `concurrency` | `8` | In-flight requests of the tenant's provider instance |
| `multi-token-policy` | `all` | When a `deviceTokens` send counts as a success: `all` or `any` |
| `max-tokens` | `500` | Largest `deviceTokens` list accepted |
| `log-token-hash` | `true` | Log targets as `sha256:<16 hex>`, else `(hidden)` |
| `token-refresh-margin` | `5m` | Refresh the access token this long before it expires (at most `30m`) |
| `endpoint`, `token-endpoint` | Google's | https, or http to a loopback address (tests) |
| `http-transport` | - | Name of an `FcmHttpTransport` bean for this tenant |
| `android.*`, `apns.*`, `webpush.*` | - | Tenant defaults for the platform blocks of every message |

**FCM credential sources.**
A service-account JSON key, as a file or inline, needs nothing else.
`adc` (Application Default Credentials: `GOOGLE_APPLICATION_CREDENTIALS`, the gcloud well-known file, or the Google Cloud metadata server) and `external-account:<path>` (workload identity federation) need `push-provider-fcm-google-auth`; without it, startup fails naming the artifact.

**FCM HTTP transport.**
The default is `JdkFcmHttpTransport.shared()`, one HTTP/2 `java.net.http.HttpClient` per JVM.
Declare an `FcmHttpTransport` bean to route through a proxy, add metrics or use another client; with several beans, `http-transport` picks one per tenant.
The google-auth adapter sends its token calls through the same transport.

**Retry-After cap.**
`notification.retry.max-retry-after`, and per channel `notification.retry.by-channel.<channel>.max-retry-after`, default to the rule's `max-delay`.

**Settings from 1.1.2**, if you upgrade from an earlier 1.1 release:

- `management.health.dlq.enabled`, `management.health.delivery-events.enabled`, `management.health.idempotency.enabled` and `management.health.rate-limit.enabled` switch the notification health indicators off one by one; they default to `management.health.defaults.enabled`.
- `notification.idempotency.retry-after-failure` (default `true`) dispatches a retry under the key of a failed attempt; `false` keeps the 1.1.0 and 1.1.1 behaviour.
- `notification.template.auto-escape` (default `false`), `notification.template.cache-ttl-seconds` (default `3600`) and `notification.template.cache-max-size` (default `1000`).
