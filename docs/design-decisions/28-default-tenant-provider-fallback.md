# Decision 28: Default-Tenant Provider Fallback

## Status: DECIDED

## Context

Every tenant that sends must have its own entry under `notification.tenants`, with its channels and providers.
A request for any other tenant fails with `TENANT_NOT_FOUND`, even though `notification.default-tenant` names a tenant whose providers would serve it.
`notification.default-tenant` only fills in a missing tenant id; it never stands in for a tenant id that is present but unconfigured.

Many deployments onboard tenants dynamically: the tenant id comes from the caller, and the tenant has no provider accounts of its own.
Those deployments today either copy the same provider block for every tenant, or put the default tenant id on every request and lose the real tenant id for idempotency, rate limits, templates and audit.

## Decision

### Property

`notification.tenant-fallback.enabled` (default `false`).
When `true`, a tenant with no entry under `notification.tenants` uses the providers of `notification.default-tenant`.
When `false`, the 1.2.0 behaviour is unchanged.

### Only provider resolution falls back

`ProviderRegistry` resolves the tenant whose configuration serves the request in one place.
`getProvider` and `isProviderRequiredInRequest` use it, so channel enablement, the default provider, and the provider instance all come from the default tenant.
Everything else keeps the request's real tenant id: the idempotency key, the rate-limit key, template lookup, audit records, dead-letter entries, metrics and error messages.
The provider instance is the default tenant's own cached instance; no copy is built for the unconfigured tenant.

### Missing default tenant

If the default tenant is itself not configured, or `notification.default-tenant` is empty, the request still fails with `TENANT_NOT_FOUND` naming the requested tenant.

### Listings

`ProviderRegistry.getAllProviders()` and `getUninitializedProviders()` list providers under the configured tenant that owns them.
A tenant served by the fallback never appears in the registry listings, the admin health endpoint, or the admin configuration endpoints, which read `notification.tenants`.

### Logging

The fallback logs at DEBUG only, with the default tenant id.
The requested tenant id is caller-controlled and is not logged, which also keeps the per-send path free of INFO noise.

## Reasoning

### Why fall back only for providers

The point of the feature is to share provider accounts, not to merge tenants.
Keeping the real tenant id everywhere else means two unconfigured tenants still have separate idempotency scopes, separate rate-limit buckets, their own template overrides when they exist, and their own audit trail.

### Why opt-in

With the fallback on, a typo in a tenant id sends through the default tenant's providers instead of failing.
That is a reasonable trade for deployments that onboard tenants dynamically, and a surprise for those that do not, so it stays off by default.

### Why reuse the default tenant's instance

Provider instances hold clients and connection pools.
Building one per unconfigured tenant would multiply those resources for identical settings and would make the registry track tenants it was never told about.

## Consequences

### Positive

- Tenants without their own provider configuration can send without any per-tenant YAML.
- The tenant id stays intact for every per-tenant feature.

### Negative

- With the fallback on, an unknown or misspelled tenant id no longer fails fast; it sends through the default tenant's providers.
- Channel enablement for an unconfigured tenant follows the default tenant; there is no per-tenant way to narrow it without configuring the tenant.

## Related Decisions

- [03-multi-tenancy.md](./03-multi-tenancy.md) - tenant resolution and per-tenant configuration.
- [05-provider-registration.md](./05-provider-registration.md) - how providers are resolved and cached.
- [29-lazy-provider-initialisation.md](./29-lazy-provider-initialisation.md) - with lazy initialisation the fallback builds the default tenant's provider on first use.
