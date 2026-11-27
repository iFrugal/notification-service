# Decision 38: Template store and admin API

## Status: DECIDED

Supersedes the "Database (future)" storage option of [DD-04](./04-template-engine.md).

## Context

Templates are `.ftl` files under `notification.template.base-path`, shipped with the application and reviewed in git.
Changing a subject line or fixing a typo for one tenant needs a code change and a deploy.
Hosts want to browse, preview, edit, publish and roll back templates at runtime, for the platform default and per tenant, without losing the git-reviewed baseline.

Before this decision the engine had two hard-wired lookup paths and no hook for either:

- top-level templates were read through Spring's `ResourceLoader` into the engine's Caffeine cache;
- `<#include>` and `<#import>` targets were read by the private `BasePathTemplateLoader` of the engine's own FreeMarker `Configuration` (DD-26).

## Decision

Everything below is opt-in.
With no template store configured the engine renders exactly what it rendered before: same files, same decoding, same errors, same log lines.

### Layering

The files stay the built-in baseline.
A template store holds published overrides only; nothing is imported into it.

```
for level in [requested tenant, default]:
    1. store: the published version of (level, channel, templateId)
    2. source: {base-path}{level}/{channel}/{templateId}.ftl (then the pre-1.1.2 location)
first hit wins; nothing found -> TEMPLATE_NOT_FOUND
```

A tenant's own built-in file therefore beats a default-layer override, and "reset" archives an override so the layer falls back to the next one.
Includes are resolved by their exact name through the same sources: a name shaped like `<tenant>/<channel>/<id>.ftl` is looked up in the store first, then in the files, without a tenant-to-default fallback.
A relative include resolves against the name of the layer that served the including template, as before.
Drafts are never rendered by the send path, and neither is `json` content.

### SPIs (`notification-api`, package `com.lazydevs.notification.api.template`)

```java
public record TemplateKey(String tenantId /* null = default layer */, String channel,
                          String templateId, String locale /* "" */) { ... }

public interface TemplateSource {                       // built-in templates
    Optional<TemplateDocument> find(TemplateKey key);   // exact key, no fallback
    Optional<TemplateDocument> findInclude(String name, Charset charset);
    default List<TemplateKey> list() { return List.of(); }
}

public interface TemplateStore {                        // editable overrides
    Optional<TemplateVersion> findPublished(TemplateKey key);
    Optional<TemplateVersion> findDraft(TemplateKey key);
    List<TemplateVersion> versions(TemplateKey key);
    List<TemplateSummary> summaries(TemplateFilter filter);
    TemplateVersion saveDraft(TemplateKey key, String contentType, String content, String sampleModel,
                              long expectedRevision, String actor, String note);
    TemplateVersion publish(TemplateKey key, long draftRevision, String actor, String note);
    TemplateVersion restore(TemplateKey key, int version, String actor, String note);
    void discardDraft(TemplateKey key, String actor, String note);
    void archive(TemplateKey key, String actor, String note);
    TemplateVersion updateApproval(TemplateKey key, int version, TemplateApprovalStatus status,
                                   String providerRef, String providerReason, String actor);
    List<TemplateEvent> events(TemplateKey key, int limit);
}

public interface TemplateChangeListener {
    void templateChanged(TemplateKey key);
    default void allTemplatesChanged() { }
}

public interface TemplateChangeFeed {
    void subscribe(TemplateChangeListener listener);
}
```

`TemplateStoreException` carries a `Reason` (`NOT_FOUND`, `NO_DRAFT`, `NO_OVERRIDE`, `UNKNOWN_VERSION`, `REVISION_CONFLICT`, `SIZE_LIMIT`, `VALIDATION_FAILED`, `READ_ONLY_CHANNEL`, `INVALID_REQUEST`), each with one HTTP status, the current revision for a conflict and `TemplateProblem`s for a failed validation.
`TemplateProblem` is `(reason, message, line, column, variablePath, templateName)`.
`TemplateRenderException` gains `getLineNumber()` and `getColumnNumber()`, filled when the failure is in the rendered template's own source.

### Engine (`notification-core`)

- `ResourceLoaderTemplateSource` is the default `TemplateSource` bean (`@ConditionalOnMissingBean(TemplateSource.class)`); it holds the previous lookup code unchanged, including the pre-1.1.2 location and its WARN, logged under the engine's category as before.
- `NotificationTemplateEngine(properties, source, store)` consults `[store, source]` at each level, for top-level templates and, through the reworked `BasePathTemplateLoader`, for includes. The existing constructor `(properties, resourceLoader)` still builds the same engine without a store.
- `evict(TemplateKey)`: a tenant key drops that tenant's cached copy, a default-layer key drops every tenant's, and FreeMarker's include cache is cleared. The engine is a `TemplateChangeListener`.
- `renderSource(key, source, model, validate)` renders unsaved content exactly as the send path would, for previews, so whatever the send path accepts (for example `?no_esc` in an auto-escaped HTML body) previews. Errors keep their line, since the section markers and the HTML output-format wrapping never add lines; on a line those changed, the column is dropped rather than reported wrong.
- A cache load that overlaps an eviction is used for that render but not kept, because a bulk removal cannot see entries still being loaded.
- Stored top-level templates are normalised like files (lines joined by `\n`, no final line break), so an override with the text of a file renders exactly like it.

### Stores

- `InMemoryTemplateStore` (`notification.template.store.type=memory`): one process, lost on restart; for tests and trying the API.
- `JdbcTemplateStore` in `notification-store-jdbc` (`notification.template.store.type=jdbc`), sharing `notification.store.jdbc.{schema,table-prefix,datasource-bean-name}` with the other JDBC stores.
  Two tables, `<prefix>template_version` and `<prefix>template_event`, are added to `JdbcStoreSchema` and `db/postgresql/notification-store.sql`; the library still never runs DDL.
  Partial unique indexes keep at most one draft and one published row per key.
  Each write runs in one transaction that takes `pg_advisory_xact_lock` on the key, so writes to a key are serialised across replicas, and appends its event in the same transaction.
- Drafts carry an optimistic `revision`: the id of the draft's latest save event, so it grows with every save and is never reused for a key, not even after a discard or a publish. A revision therefore names exactly one draft content: a stale editor cannot overwrite a newer draft, and a publish validated against one draft cannot publish a replacement. `expectedRevision` 0 means "there was no draft".
  A draft is version 0 until it is published; published versions are numbered from 1 per key; restore publishes a copy as the next version.
- Content and sample model are each capped by `notification.template.store.max-content-size` (default 256 KiB of UTF-8).
- Every write needs an actor (a plain string from the host); the library never authenticates.

### `TemplateAdminService` (`notification-core`, framework-free)

`list`, `tenantOverrides`, `get`, `preview`, `validate`, `saveDraft`, `publish`, `restore`, `discard`, `reset`, `versions`, `events`.

- `get` returns the layering a UI needs: the effective source and version, this layer's built-in content, what the layer inherits without its own override (source and content), the published version, the draft and the sample model (draft's, else published's, else a host `TemplateSampleProvider`'s, else empty).
- `publish` requires a note and refuses with `VALIDATION_FAILED` unless the draft fits the size limit, parses, renders with its stored sample model and, for email, has a subject and a body.
- `restore` is not validated again: it is the way back from a bad publish.
- Publish, restore and reset evict the service's engine at once and then tell the other local listeners.
- Writes to channels not in `notification.template.store.editable-channels` (default `email, sms, push`) are refused with `READ_ONLY_CHANNEL`; WhatsApp bindings are read-only by default because a changed binding needs a matching approval at Meta.

### REST (`notification-rest`)

`TemplateAdminController` under `<base-path>/admin/templates`, behind `notification.rest.admin.templates.enabled` (default `false`) on top of `notification.rest.enabled`, with full OpenAPI annotations.
The layer is the `tenantId` query parameter on every endpoint (absent is the default layer).
The actor comes from an `ActorResolver` bean (default: the servlet principal's name); a write without one is a 401.
As with the other admin endpoints, no authentication ships (DD-08); the host secures the path.
Enabling the switch without a store fails startup.

| Method | Path | Operation |
|---|---|---|
| GET | `/admin/templates?channel=` | list |
| GET | `/admin/templates/tenant-overrides?channel=&templateId=` | tenant overrides |
| GET | `/admin/templates/{channel}/{templateId}` | get |
| POST | `.../preview`, `.../validate` | preview, validate (200 with `ok` and `problems`) |
| PUT, DELETE | `.../draft` | save draft (409, 413), discard (204, 404) |
| POST | `.../publish` | publish (400 without a note, 409, 422 with `errors`) |
| POST | `.../versions/{version}/restore` | restore |
| POST | `.../reset` | reset (204, 404) |
| GET | `.../versions`, `.../events` | versions and audit trail, newest first |

### Push templates (with DD-37)

The push renderer (`PushContentRenderer`, DD-37) loads templates through `NotificationTemplateEngine.renderRaw`, which uses the same layered lookup, so a published push override is sent with its `[TITLE]`, `[BODY]` and `[DATA]` sections and renders with the SAFER resolver.
`TemplateAdminService` previews and validates a FreeMarker template of a channel that has a `ChannelContentRenderer` through that renderer: `NotificationTemplateEngine.renderCandidate(key, source, action)` lets unsaved content stand in for exactly that template on the calling thread, uncached, while the renderer runs.
A push draft with a broken section is therefore refused at publish, and its preview returns the title, the body and the `PushContent` payload.

### Safety

Any template store requires `notification.template.legacy-helpers=false`; the engine refuses to start otherwise.
Stored templates always render with FreeMarker's `SAFER_RESOLVER`, set on the template itself.

### Cache invalidation across replicas

The publishing process evicts locally.
`JdbcTemplateChangeFeed` polls the event table every `notification.template.store.poll-interval` (default 15 s) and tells every `TemplateChangeListener` bean (the engine, the store-backed WhatsApp registry, the host's own) which keys changed.
It starts below the events of the last late-commit window, so an event whose transaction was still open at startup is delivered when it commits, and it holds no connection between polls.
Identity ids are assigned at insert, not at commit, so an id can become visible after a higher one: the feed re-reads the ids it delivered within a late-commit window (a minute, or four poll intervals if longer) and only moves its checkpoint past an id once the window has passed.
Missing ids (rolled-back inserts, deleted rows) hold nothing up.

### Row-level security

Keys carry their tenant; the JDBC store never reads `TenantContext`.
A host with row-level security sets the session tenant itself, for example the target tenant for a tenant override and a pool session for the default layer.
A session that sees only its own tenant cannot list other tenants' overrides or see their events, so `notification.store.jdbc.template.summary-function` and `.change-function` name `SECURITY DEFINER` functions that return keys and metadata only; the module README has reference definitions, and `JdbcTemplateStoreRlsIT` runs them against policies that let every tenant read the default layer and only the pool session write it.

### WhatsApp

`StoreBackedWhatsAppTemplateRegistry` in `whatsapp-core` implements the unchanged `WhatsAppTemplateRegistry`.
It reads `json` bindings (`name`, `language`, `header`, `body`, `buttons[index, subType, variable]`) published under channel `whatsapp`, at each level before the YAML binding of that level.
A stored binding is returned only while its approval status is `APPROVED` or not tracked (`null`); any other status, or a binding that does not parse, yields no binding at all, so the send fails before the claim instead of reaching Meta with a template it would reject.
Lookups are cached (template cache TTL and size) and dropped by the change feed.
It replaces the YAML registry bean whenever a `TemplateStore` bean exists.

### Configuration

| Property | Default | Meaning |
|---|---|---|
| `notification.template.store.type` | `none` | `none`, `memory` or `jdbc` |
| `notification.template.store.poll-interval` | `15s` | JDBC change feed poll interval |
| `notification.template.store.max-content-size` | `256KB` | Limit for content and sample model |
| `notification.template.store.editable-channels` | `email, sms, push` | Channels the admin API may write |
| `notification.rest.admin.templates.enabled` | `false` | Expose the REST API |
| `notification.store.jdbc.template.summary-function` | unset | `SECURITY DEFINER` listing function |
| `notification.store.jdbc.template.change-function` | unset | `SECURITY DEFINER` change-feed function |

## Changes from the design sketch, and why

- `TemplateStore` does not extend `TemplateSource`.
  The built-in source is replaceable with `@ConditionalOnMissingBean(TemplateSource.class)`; a store bean of that type would switch the built-in source off.
  The engine adapts the store into a source internally instead, so both lookup paths still go through `TemplateSource`.
- `TemplateSource` has an include method that takes a charset.
  Built-in includes were decoded with the charset FreeMarker asks for, which honours an `<#ftl encoding>` header; keeping that exact needs the source to be able to read the bytes again.
- The sample model is a `TEXT` column, not `jsonb`, like every other JSON column of the JDBC stores; the admin service only accepts JSON objects.
- Drafts get version 0 and their number at publish, so a restore between saving and publishing a draft cannot collide with it.
- Draft rows carry `updated_by` and `updated_at`, which the editor's conflict message needs.
- A draft's revision is its latest save event's id rather than a counter starting at 1 per draft: a per-draft counter repeats after a discard or a publish, which would let a stale editor or an earlier validation match a different draft.
- The event column is `occurred_at`, not `at`.
- The change feed reads events after its checkpoint and re-reads a late-commit window instead of comparing `max(id)`, which would miss ids that commit out of order.
- Cross-tenant listing and the change feed under row-level security go through optional functions instead of being a host problem only.
- The service evicts its own engine on publish directly, instead of relying on listener wiring.
- WhatsApp is read-only by default (`editable-channels`) rather than hard-coded, and no `TemplateApprovalClient` ships; `updateApproval` records statuses a host syncs itself.

## Consequences

- Hosts can change templates at runtime with drafts, validation, history, one-click restore and an audit trail, without giving up the files in git.
- A replica serves a replaced template for at most one poll interval; the publishing replica serves it at once.
- Drift between files and overrides is visible (`get` returns both), and any version can be copied back into git.
- A store failure on the send path fails the render like any other template error; the engine cache keeps serving what it has for the cache TTL.
- The library stays free of authentication: the host decides who may call the API and names the actor.

## Testing

- `TemplateStoreOffRegressionTest`: the existing constructor, the source constructor and an empty store render a fixture matrix (HTML and text email, includes and imports, CRLF, the pre-1.1.2 location, an include with an `<#ftl encoding>` header, syntax and model errors) to the same written-out results.
- `NotificationTemplateEngineStoreTest`: the resolution table, includes through the store, eviction scopes, SAFER, the legacy-helpers guard, and render error locations.
- `InMemoryTemplateStoreTest`, `TemplateAdminServiceTest`, `TemplateAdminControllerTest`, `StoreBackedWhatsAppTemplateRegistryTest`, and the starter's `TemplateStoreStarterTest` for the wiring.
- `JdbcTemplateStoreIT` (lifecycle, size limit, concurrent restores and publishes), `JdbcTemplateStoreRlsIT` (policies and the two functions) and `JdbcTemplateChangeFeedIT` (two engines on one database, out-of-order commits, missing ids) on Testcontainers PostgreSQL.
