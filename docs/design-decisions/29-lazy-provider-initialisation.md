# Decision 29: Lazy Provider Initialisation

## Status: DECIDED

## Context

`ProviderRegistry` builds every provider of every enabled channel at startup: it resolves the bean, calls `configure()` and then `init()`.
Any exception becomes a `ProviderConfigurationException` and stops the application.

Fail-fast startup is right when every provider's settings are known at deploy time.
It is wrong for an application that ships with a channel enabled before its settings exist, for example an email provider whose endpoint and sender arrive later from a secret store or a refreshed property source.
Such an application cannot boot at all, so nothing else it does works either.

Conditional beans do not help: under Spring AOT and GraalVM native images, bean conditions are evaluated at build time and frozen, so an application cannot switch a provider on or off by the presence of a setting at runtime.

## Decision

### Property

`notification.providers.lazy-init` (default `false`).
When `false`, startup is unchanged.
When `true`, startup validates the default-provider configuration (more than one `default: true` still fails) but does not resolve, configure or initialise any provider.

### First use builds the provider

The first `getProvider` for a `(tenant, channel, provider)` builds it: resolve, inject the delivery-event publisher, `configure()`, `init()`, then cache it like an eager build.
Concurrent first callers share one build through a per-provider `CompletableFuture` registered with `putIfAbsent`; the winner checks the cache again before building, so a build that just finished is reused.
The in-flight entry is removed when the build ends, whether it succeeded or failed.

### A failed build is not cached

When the build throws, `getProvider` returns an `UnavailableProvider` stand-in instead of throwing.
Its `send` returns `SendResult.failure("PROVIDER_NOT_CONFIGURED", reason, FailureType.TRANSIENT)`.
`DefaultNotificationService` is unchanged: it treats the result like any provider failure, so the caller gets `FAILED` with error code `PROVIDER_NOT_CONFIGURED`, the audit records it, the retry policy applies, and the dead-letter store keeps it with `failureType=TRANSIENT`.
The next lookup tries the build again.
A retry of the same send calls the stand-in again, and from the second call on the stand-in asks the registry to build the provider and delegates to it when that works.

The first failure for a provider, and every failure whose message changes, is logged at WARN; repeats are logged at DEBUG.

### Lifecycle and health

`destroyProviders()` destroys every provider that was built, eagerly or lazily; stand-ins are never cached and never destroyed.
`ProviderRegistry.getUninitializedProviders()` lists the configured providers of enabled channels that have no instance yet, as `NOT_INITIALIZED` or, after a failed build, `INIT_FAILED`.
The admin health endpoint lists them under `providers`: `NOT_INITIALIZED` keeps the status `UP`, `INIT_FAILED` makes it `DEGRADED`, the same as an unhealthy built provider.

## Reasoning

### Why a stand-in provider and not an exception

`DefaultNotificationService` turns every `NotificationException` from `getProvider` into a `PERMANENT` failure, and any other exception into `UNKNOWN` with code `INTERNAL_ERROR`.
A missing setting is neither: it is expected to be fixed, and the send should be retried or replayed once it is.
Returning a provider whose send fails as `TRANSIENT` gets that classification through the existing failure path without touching the service, so the retry policy and the dead-letter store behave exactly as for any other transient provider failure.

### Why the stand-in rebuilds on a retry

Without it, the retry executor would call the same failing stand-in for every attempt and wait its backoff for nothing.
The first call reports the failure the lookup just saw, so one send does not build twice in a row.

### Why `NOT_INITIALIZED` is not a health failure

The point of lazy initialisation is that an application with missing settings is healthy until something needs them.
Reporting `DOWN` or `DEGRADED` for a provider no send has used would make orchestrators restart or withhold traffic from an application that is working as configured.
A failed build is different: a send has failed, and an operator should see it.

### Why one build under contention

Provider `init()` can open connection pools or fetch credentials.
A burst of first sends must not build several instances, leak all but one, and hit the provider's token endpoint in parallel.
Sharing the in-flight build also means a failure is reported to every waiting caller once, instead of each caller retrying the build in turn.

## Consequences

### Positive

- An application boots even when an enabled provider's settings are missing, including in a native image.
- A missing setting surfaces as a retryable, dead-lettered send failure with a clear error code.
- Default behaviour is unchanged.

### Negative

- Configuration mistakes that eager initialisation catches at startup now surface on the first send.
- A provider with a slow `init()` adds that time to the first send that needs it.
- A provider name that matches no bean or class is reported on first use, not at startup.

## Related Decisions

- [05-provider-registration.md](./05-provider-registration.md) - provider resolution and lifecycle.
- [13-retries-and-dlq.md](./13-retries-and-dlq.md) - failure classification, retries and the dead-letter store.
- [21-health-indicators.md](./21-health-indicators.md) - health reporting.
- [28-default-tenant-provider-fallback.md](./28-default-tenant-provider-fallback.md) - the fallback builds the default tenant's provider on first use.
