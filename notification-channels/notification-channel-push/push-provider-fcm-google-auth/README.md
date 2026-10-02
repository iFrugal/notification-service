# push-provider-fcm-google-auth

Optional credential sources for [push-provider-fcm](../push-provider-fcm/README.md), built on the [Google auth library](https://github.com/googleapis/google-auth-library-java): Application Default Credentials and workload identity federation.
Available since 1.2.0.

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>push-provider-fcm-google-auth</artifactId>
    <version>${notification-service.version}</version>
</dependency>
```

It brings `push-provider-fcm` with it.
Nothing else needs configuring: the module registers its `FcmAccessTokenProviderFactory` in `META-INF/services`, and `FcmPushProvider` finds it for the `credentials` values below.
Without this module, those values fail at startup with a message that names `com.github.ifrugal:push-provider-fcm-google-auth`.

You do not need it for a service-account JSON key, as a file or inline: `push-provider-fcm` handles that on its own, without any Google library.

## Credential sources

| `credentials` | Source |
|---------------|--------|
| `adc` | Application Default Credentials, in the library's order: the file named by `GOOGLE_APPLICATION_CREDENTIALS`, the gcloud well-known file (`gcloud auth application-default login`), then the Google Cloud runtime (the metadata server of GKE, Cloud Run, Compute Engine and App Engine). |
| `external-account:<path>` | A workload identity federation credential configuration (`"type": "external_account"`), as written by `gcloud iam workload-identity-pools create-cred-config`: a subject token from a file, a URL, an executable, or AWS, exchanged at Google's Security Token Service, with or without service account impersonation. |

```yaml
notification:
  tenants:
    acme:
      channels:
        push:
          enabled: true
          providers:
            fcm:
              properties:
                credentials: adc
                project-id: acme-firebase   # required unless the credentials name a project
---
notification:
  tenants:
    globex:
      channels:
        push:
          enabled: true
          providers:
            fcm:
              properties:
                credentials: external-account:/var/run/secrets/fcm/wif-config.json
                project-id: globex-firebase
```

The credentials are loaded with the type-specific loaders `GoogleCredentials.getApplicationDefault(transportFactory)` and `ExternalAccountCredentials.fromStream(stream, transportFactory)`, then scoped to `https://www.googleapis.com/auth/firebase.messaging`.
The identity needs permission to send with the Firebase Cloud Messaging API, for example the role `Firebase Cloud Messaging API Admin`, granted to the service account or, without impersonation, to the federated principal.

**Project id.**
The tenant's `project-id` wins.
Otherwise the project comes from the credentials when they name one without a network call: `project_id` of a service-account key found through ADC, else the `quota_project_id` of the configuration (or `GOOGLE_CLOUD_QUOTA_PROJECT`).
On the Google Cloud runtime set `project-id`: the metadata server is not asked.

**Startup.**
`configure()` reads and parses the credential file and makes no token call.
For `adc` it resolves Application Default Credentials there, so a host without any credentials fails at startup rather than at the first send; only when no credential file is found does that probe the metadata server, through the tenant's transport.
The library resolves ADC once per JVM and keeps the result for every tenant that uses `adc`.

**Settings that apply.**
`timeout` bounds every HTTP call of the library (the library's own connect plus read timeout, at most `timeout`), and `token-refresh-margin` decides when the token is refreshed.
`token-endpoint` does not apply and logs a warning: the credential configuration names its own endpoints.

## Every call goes through your FcmHttpTransport

All HTTP traffic of the Google auth library goes through the tenant's `FcmHttpTransport`, the same transport the FCM sends use: the token exchanges, service account impersonation, the metadata server, and URL-sourced or AWS subject tokens.
The library's own `NetHttpTransport` is never used, so a proxy, client metrics or a custom HTTP client configured as an `FcmHttpTransport` bean also covers the token calls.

- `FcmHttpTransportAdapter` extends google-http-client's `HttpTransport` over an `FcmHttpTransport`.
  It passes the method, URI, headers, body and timeout through, and hands status, headers and body back; the library decompresses a gzip answer itself.
  Framing headers (`Content-Length`, `Host`, `Connection`, `Expect`, `Upgrade`, `Transfer-Encoding`) are left to the transport.
- For `external-account:` each tenant's credentials get their own adapter.
- For `adc` the library caches one credential object per JVM together with the transport factory of its first caller.
  The module therefore hands it a JVM-wide factory that sends each request through the adapter of the tenant whose token call is running on that thread (a `ScopedValue` binding), and refuses any request made outside a token call.
- A custom `FcmHttpTransport` sees `GET` and `PUT` requests as well as `POST` (the metadata server, AWS IMDSv2, URL-sourced subject tokens) and plain-http requests to the link-local metadata address.

The module's tests prove the guarantee: every credential file names `https://*.invalid` hosts, which never resolve, and a test transport sends them to a loopback stub, so a call that bypassed the transport would fail; the number of requests the stub answers equals the number the transport saw; and the library's fallback `HttpTransportFactory`, replaced in the tests by one that counts, is never asked for a transport.

## Tokens and failures

The token is cached until `token-refresh-margin` before it expires, judged with the provider's clock.
Then, and after FCM rejects the token (a 401 without an FCM error, which invalidates it), the next send calls `GoogleCredentials.refresh()`; concurrent sends wait for that one refresh.
With impersonation, the library keeps the STS token for its own lifetime and only the impersonation call is repeated.

Token failures fail the send with error code `FCM_AUTH_FAILED`:

| Failure | Classification |
|---------|----------------|
| no answer from an endpoint (`FcmTransportException`) | `TRANSIENT`: a token call carries no notification, so it is always safe to retry |
| 408, 425, 429 or 5xx from an endpoint | `TRANSIENT` |
| any other 4xx (for example `invalid_grant` from STS, or a 403 from the impersonation call) | `PERMANENT` |
| anything else (for example an unreadable subject token file) | `UNKNOWN` |

For a service-account key the library itself retries the token call on 408, 429, 500 and 503 a few times, with backoff, before it fails.
Error messages carry the library's description of the failure, which names endpoints and OAuth error codes; no access token, subject token or key appears in a log line or an error message.
The library's own `java.util.logging` output stays free of secrets too, because it disables google-http-client's request logging for its service-account, STS and impersonation token calls; the module's tests check that at the most verbose level.

## Trust in the credential files

The credential files are trusted configuration, like any secret mount.
Unlike the built-in service-account support of `push-provider-fcm`, which ignores a `token_uri` that is not Google's, the library honours the `token_uri` of a key found through ADC and every URL of an external account configuration (it requires https).
Google's [guidance on externally sourced credentials](https://cloud.google.com/docs/authentication/external/externally-sourced-credentials) applies: never load a configuration that an outside party can supply.

## Dependency footprint

`mvn dependency:tree` of this module with the root pom's dependency management (Spring Boot 4.1.1 BOM), beyond what `push-provider-fcm` already brings:

| Artifact | Version | Size |
|----------|---------|------|
| `com.google.auth:google-auth-library-oauth2-http` | 1.54.0 | 345 KB |
| `com.google.auth:google-auth-library-credentials` | 1.54.0 | 9 KB |
| `com.google.http-client:google-http-client` | 2.2.0 | 300 KB |
| `com.google.http-client:google-http-client-gson` | 2.2.0 | 13 KB |
| `com.google.code.gson:gson` | 2.13.2 (Boot) | 290 KB |
| `com.google.guava:guava` (with `failureaccess`, `listenablefuture`) | 33.6.0-jre | 3.08 MB |
| `com.google.api:api-common` | 2.70.0 | 51 KB |
| `io.opencensus:opencensus-api`, `opencensus-contrib-http-util` | 0.31.1 | 379 KB |
| `io.grpc:grpc-context`, `grpc-api` | 1.83.1 (Boot) | 345 KB |
| annotations: `jspecify`, `jsr305`, `error_prone_annotations`, `j2objc-annotations`, `auto-value-annotations` | | 68 KB |
| **Total** | | **about 4.9 MB** |

There is no Netty and no Apache HttpClient.

**Excluded.**
The root pom's dependency management excludes `org.apache.httpcomponents:httpclient` 4.5.14 and `httpcore` 4.4.16 (with `commons-codec`, 1.5 MB together).
In google-http-client they back only `ApacheHttpTransport`, which nothing uses, because every call goes through `FcmHttpTransportAdapter`.
The exclusions are part of this module's published pom, so an application that depends on it does not get them either.
OpenCensus and gRPC context cannot be excluded: google-http-client's `HttpRequest` opens an OpenCensus span for every call, and OpenCensus keeps it in an `io.grpc.Context`; without them the first token call fails with `NoClassDefFoundError`.

**Spring Boot's BOM.**
Spring Boot manages gson and gRPC, so in a Boot application they resolve to Boot's versions, not the library's: gson 2.13.2 instead of 2.14.0, and grpc-context and grpc-api 1.83.1 instead of 1.70.0.
Guava, OpenCensus and the Google artifacts are not managed by Boot.
The module's tests run against the Boot-managed versions.

## GraalVM native image

The Google auth library and google-http-client ship their own `META-INF/native-image` metadata.
Native use of this module is not yet claimed or tested in CI; follow step 5 of the smoke plan in the [push-provider-fcm README](../push-provider-fcm/README.md#graalvm-native-image).
