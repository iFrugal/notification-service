# email-provider-acs

Email provider for [Azure Communication Services (ACS) Email](https://learn.microsoft.com/azure/communication-services/concepts/email/email-overview).
Provider name `acs` on the `EMAIL` channel.

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>email-provider-acs</artifactId>
    <version>${notification-service.version}</version>
</dependency>

<!-- Only when authenticating with credential: default (managed identity etc.) -->
<dependency>
    <groupId>com.azure</groupId>
    <artifactId>azure-identity</artifactId>
</dependency>
```

## Configuration

Like every provider in this library, ACS is configured per tenant.
`ProviderRegistry` creates one provider instance per tenant, calls `configure(properties)` with the tenant's provider properties merged over the channel-level `config`, then `init()`.
There is no global `notification.email.acs.*` namespace.

```yaml
notification:
  tenants:
    default:
      channels:
        email:
          enabled: true
          config:
            from-address: DoNotReply@notify.example.com   # used when 'sender' is absent
          providers:
            acs:
              bean-name: acsEmailProvider   # selects the auto-configured prototype bean
              properties:
                connection-string: ${ACS_CONNECTION_STRING}
                sender: DoNotReply@notify.example.com
                reply-to: support@example.com
                send-mode: wait
                wait-timeout: PT60S
```

If your notification-core version lists `EMAIL:acs` in its provider catalog, the provider name `acs` is enough on its own.
`bean-name: acsEmailProvider` selects the same bean explicitly, and works on every version.

| Key | Default | Description |
|-----|---------|-------------|
| `connection-string` | - | ACS connection string (`endpoint=https://<resource>.communication.azure.com/;accesskey=<key>`). Wins over `endpoint` when both are set. |
| `endpoint` | - | ACS resource endpoint, used together with `credential`. |
| `credential` | - | `default` for `DefaultAzureCredential` (needs `azure-identity`), otherwise the name of a `TokenCredential` bean. Blank with `endpoint` uses the application's only `TokenCredential` bean. |
| `sender` | channel `from-address` | Sender address. It must be a MailFrom address of a domain connected to the ACS resource. Required. |
| `reply-to` | - | Default reply-to addresses, comma-separated or a YAML list. The recipient's own `replyTo` overrides it. |
| `send-mode` | `wait` | `wait` polls until ACS reports a final status. `submit` returns once ACS accepted the message. |
| `wait-timeout` | `PT60S` | ISO-8601 upper bound for `wait` mode. |
| `sdk-retries` | `0` | Retries performed inside the Azure SDK. Keep `0` so the library's `RetryExecutor` is the only retry layer. |
| `user-engagement-tracking-disabled` | ACS resource setting | `true` disables open and click tracking for messages from this tenant. |

Configuration errors fail at startup with a `ProviderConfigurationException` that names the missing or malformed key.

## Authentication

Pick one of three options.

1. **Connection string.**
   Set `connection-string`.
   No extra dependency is needed.
2. **Managed identity, workload identity, environment or Azure CLI credentials.**
   Set `endpoint` and `credential: default`, and add `com.azure:azure-identity` to your application.
   The provider builds a `DefaultAzureCredential`.
   The identity needs a role assignment on the Communication Services resource that allows sending email.
3. **Your own `TokenCredential` bean.**
   Declare the bean and set `endpoint` and `credential: <bean name>`.
   Different tenants can use different credential beans.
   If the application has exactly one `TokenCredential` bean, `credential` can be left blank.

Bean-named credentials need the Spring bean `acsEmailProvider`.
A provider created through `fqcn:` or reflection has no access to the application context, so it rejects `credential: <bean name>` with a clear message.
Connection strings and `credential: default` work in both cases.

## Registration

`AcsEmailProviderAutoConfiguration` registers the bean `acsEmailProvider` whenever this jar and the ACS SDK are on the classpath.
The bean is a prototype, so every tenant gets its own configured instance.
A bean that you declare under the same name replaces it.
The class `com.lazydevs.notification.channel.email.acs.AcsEmailProvider` also has a public no-arg constructor for `fqcn:` use.

## Send modes

ACS sends email as a long-running operation.
`EmailClient.beginSend` submits the message, and its status is then read by polling.
The provider message id (`SendResult.messageId`) is the ACS operation id in both modes.

- **`wait`** (default) polls until ACS reports `Succeeded` or `Failed`, at most `wait-timeout`.
  `Succeeded` is a success.
  `Failed` is a `PERMANENT` failure that carries the ACS error code and message.
  A timeout is a `TRANSIENT` failure that keeps the operation id.
  The message may still be delivered after a timeout, so a retry can produce a duplicate.
  Use the operation id to reconcile.
- **`submit`** returns as soon as ACS accepted the message.
  The SDK's poller does not expose the operation id from the submit response, so this mode costs one extra status poll per message to read it.
  The final delivery result is not observed.
  Use ACS delivery reports through Event Grid if you need it.
  `SendResult.providerMetadata.acsStatus` is `SUBMITTED`.

## Failure classification and retries

`AcsEmailProvider.classifyAcs` maps SDK errors for the library's `RetryExecutor`.

| Error | Classification |
|-------|----------------|
| HTTP 429, 408, 5xx | `TRANSIENT` |
| HTTP 401, 403 | `PERMANENT`, and the error message suggests checking the credentials |
| Other HTTP 4xx | `PERMANENT` |
| Timeouts and I/O errors | `TRANSIENT` |
| Anything else | `UNKNOWN` (the retry predicate decides) |
| Request that cannot become an ACS message (no body, URL-only attachment) | `PERMANENT`, and ACS is not called |

The Azure SDK's built-in retry policy retries 408, 429 and 5xx three times by default.
The provider sets the policy to `sdk-retries` (default 0) so that retries are not multiplied across two layers.
ACS sends `Retry-After` with 429.
`SendResult` has no field for a delay hint, so the `RetryExecutor` backoff applies.

## Message mapping

| Library field | ACS field |
|---------------|-----------|
| `sender` setting | `senderAddress` |
| rendered subject, else `EmailRecipient.subject` | `subject` |
| `EmailRecipient.to` / `cc` / `bcc` | `toRecipients` / `ccRecipients` / `bccRecipients` |
| rendered HTML and text bodies | `bodyHtml` / `bodyPlainText` |
| `EmailRecipient.replyTo`, else `reply-to` setting | `replyTo` |
| `NotificationRequest.attachments` with inline `content` | `attachments` (content type defaults to `application/octet-stream`) |

ACS needs attachment bytes inline.
An attachment that only has a `url` is rejected as a `PERMANENT` failure instead of being dropped.
The request model has no per-message sender or custom header fields, so neither is mapped.

## Transport

The module excludes `azure-core-http-netty` and uses `azure-core-http-jdk-httpclient`, which is built on `java.net.http.HttpClient`.
The Netty transport pins Netty 4.1, which clashes with the Netty 4.2 that Spring Boot 4.1 manages.
This module brings in no Netty and no reactor-netty.
`azure-core` still depends on `reactor-core`, whose version comes from the Spring Boot BOM.
All ACS clients in one JVM share one JDK `HttpClient`.

## Rate limits

ACS throttles with HTTP 429, and the provider classifies 429 as `TRANSIENT`.
Default sending limits are low, especially on Azure-managed domains (`*.azurecomm.net`), which are meant for getting started.
Use a custom verified domain and request a quota increase before production traffic.
Consider a library rate-limit rule for the email channel that stays below your ACS quota.

## GraalVM native image

The module uses no reflection of its own.
`azure-core` ships its own native-image configuration.
`credential: default` pulls MSAL (`msal4j`) through `azure-identity`, and MSAL may need additional reachability metadata.
Verify a native build with your chosen authentication option.
