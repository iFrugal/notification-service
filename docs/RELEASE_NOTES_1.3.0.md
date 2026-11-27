# notification-service 1.3.0

1.3.0 builds on 1.2.3: everything in the [1.2.3 release notes](./RELEASE_NOTES_1.2.3.md) applies.
It adds an opt-in template store with an admin API (DD-38).
Without the new configuration nothing behaves differently: the engine reads, decodes, includes and reports errors on your template files exactly as before.

## Highlights

- **Change templates at runtime.**
  A template store holds published overrides of the template files, for the default layer (every tenant) or for one tenant.
  The files stay the git-reviewed baseline; "reset" archives an override and the layer falls back to the file again.
  Resolution, first hit wins: the tenant's override, the tenant's file, the default override, the default file.
  Includes are looked up by their exact name, the override of that name before the file.
- **Drafts, validated publish, history and one-click restore.**
  Drafts are guarded by an optimistic revision; publishing needs a note and is refused unless the draft parses, renders with the sample model stored with it and, for email, has a subject and a body.
  Problems carry the line, the column and the data-model path.
  Every change is kept in an audit trail with the actor the host names.
- **`TemplateAdminService`**, framework-free in `notification-core`: list, get (with the layering an editor needs), preview (subject, HTML and text), validate, save draft, publish, restore, discard, reset, versions and events.
- **Optional REST API** under `<base-path>/admin/templates`, with complete OpenAPI annotations and an `ActorResolver` hook; off unless `notification.rest.admin.templates.enabled=true`.
- **In-memory and PostgreSQL stores.**
  `InMemoryTemplateStore` ships in core; `JdbcTemplateStore` in `notification-store-jdbc` serialises writes per key with an advisory lock and works under row-level security.
- **Replicas stay in step.**
  The publishing process evicts its cache at once; `JdbcTemplateChangeFeed` tells the other replicas within `notification.template.store.poll-interval` (15 s).
- **WhatsApp bindings from the store.**
  With a store, the WhatsApp registry reads JSON bindings published under channel `whatsapp` before the YAML ones, and only returns one while its approval status is `APPROVED` or not tracked.
- **Push templates too.**
  The push renderer of 1.2.3 reads templates through the same lookup, so a stored push override is sent with its `[TITLE]`, `[BODY]` and `[DATA]` sections, and preview and publish validation run through the push renderer, so a draft with a broken section is refused before it goes live.
- **Built-in templates from anywhere.**
  The new `TemplateSource` SPI serves both the top-level and the include lookups; the default reads `notification.template.base-path` as before, and a bean of your own replaces it.

## Enabling it

```yaml
notification:
  template:
    legacy-helpers: false        # required: a store does not start with the legacy helpers on
    store:
      type: jdbc                 # or memory
  rest:
    admin:
      templates:
        enabled: true            # optional
```

For `jdbc`, add `notification-store-jdbc` and a `DataSource`, and create `<prefix>template_version` and `<prefix>template_event` from the reference DDL (`db/postgresql/notification-store.sql`, or `JdbcStoreSchema`).
The tables are additive; the existing ones do not change.
Under row-level security, see the [module README](../notification-store-jdbc/README.md#template-store-under-row-level-security) for the reference policies and the two optional `SECURITY DEFINER` functions.

## New configuration

| Property | Default |
|---|---|
| `notification.template.store.type` | `none` |
| `notification.template.store.poll-interval` | `15s` |
| `notification.template.store.max-content-size` | `256KB` |
| `notification.template.store.editable-channels` | `email, sms, push` |
| `notification.rest.admin.templates.enabled` | `false` |
| `notification.store.jdbc.template.summary-function` | unset |
| `notification.store.jdbc.template.change-function` | unset |

## Behaviour changes

None without a template store.
With one:

- Startup fails unless `notification.template.legacy-helpers=false`, and stored templates render with FreeMarker's `SAFER_RESOLVER`.
- `notification.template.store.type=jdbc` without `notification-store-jdbc`, or `notification.rest.admin.templates.enabled=true` without a store, fails startup with a message naming what is missing.
- The `WhatsAppTemplateRegistry` bean is a `StoreBackedWhatsAppTemplateRegistry` wrapping the YAML one; code that injects `PropertiesWhatsAppTemplateRegistry` by its class should inject `WhatsAppTemplateRegistry` instead.

## API additions and compatibility

Every change to the public API is additive.

- `com.lazydevs.notification.api.template`: `TemplateKey`, `TemplateSource`, `TemplateDocument`, `TemplateOrigin`, `TemplateStore`, `TemplateVersion`, `TemplateVersionStatus`, `TemplateApprovalStatus`, `TemplateSummary`, `TemplateFilter`, `TemplateEvent`, `TemplateEventAction`, `TemplateProblem`, `TemplateStoreException`, `TemplateStoreChecks`, `TemplateChangeListener`, `TemplateChangeFeed`.
- `TemplateRenderException` gains a constructor with a line and a column, and `getLineNumber()` and `getColumnNumber()`, filled when the failure is in the rendered template's own source.
- `NotificationTemplateEngine` gains the constructor `(NotificationProperties, TemplateSource, TemplateStore)`, `evict(TemplateKey)`, `renderSource(...)` and `isStoreConfigured()`, and implements `TemplateChangeListener`; the `(NotificationProperties, ResourceLoader)` constructor is unchanged.
- `notification-core`: `ResourceLoaderTemplateSource` (the default `TemplateSource` bean), `TemplateChangeDispatcher`, `store.InMemoryTemplateStore`, `store.TemplateStoreType`, and `admin.TemplateAdminService` with `TemplateDetail`, `TemplateListItem`, `TemplateCheckResult`, `TemplateLayerSource` and `TemplateSampleProvider`.
- `notification-store-jdbc`: `JdbcTemplateStore`, `JdbcTemplateChangeFeed`, `JdbcTemplateStoreAutoConfiguration`; `JdbcStoreTables.templateVersion()` and `templateEvent()`; `JdbcStoreSchema` renders five more statements.
- `notification-rest`: `TemplateAdminController`, `ActorResolver`, `TemplateAdminBodies`.
- `whatsapp-core`: `StoreBackedWhatsAppTemplateRegistry`.

## Design

[DD-38: Template store and admin API](./design-decisions/38-template-store-and-admin-api.md).
