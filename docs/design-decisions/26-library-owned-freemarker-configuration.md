# Decision 26: Library-Owned FreeMarker Configuration

## Status: DECIDED

## Context

Up to 1.2, `NotificationTemplateEngine` loaded the template source through Spring's `ResourceLoader` (tenant directory first, then `default/`, under `notification.template.base-path`) and handed the text to the persistence-utils `TemplateEngine` singleton.
That singleton owns one process-wide FreeMarker `Configuration` (incompatible improvements 2.3.23, a `ClassTemplateLoader` on `/templates` and an optional `FileTemplateLoader` on `./templates`) and renders every source as `new Template("", source, cfg)`.

Four problems follow from that.

**Includes ignore the base path.**
`<#include>` and `<#import>` resolve against persistence-utils' loaders, not against `base-path` and not relative to the including template, because the template has no name.
A deployment that keeps its templates in `file:/etc/app/templates/` cannot share a header between them.

**Every template gets code-execution helpers.**
`generate()` puts `uuid`, `eval`, `file`, `trim`, `js`, `serde_deserializeToMap` and `serde_serialize` into every model.
`file` reads and renders any path the process can read, `eval` renders a string, and `js` calls a process-wide script engine.
`?new` is unrestricted as well, so `"freemarker.template.utility.Execute"?new()` runs a shell command.
The 1.1.2 release notes list these as known limitations.

**Errors are logged twice, with stack traces, by code the application cannot configure.**
FreeMarker logs every `TemplateException` with its stack trace to `freemarker.runtime` before rethrowing it, and persistence-utils wraps it in a bare `RuntimeException`.
The `Configuration` is shared with other users of persistence-utils in the same process, so an application cannot turn that logging off without changing their behaviour too.

**Errors carry no structure.**
A missing variable surfaces as `RuntimeException("Exception when replacing values ...")`, which the send path reports as `INTERNAL_ERROR`.
Nothing tells the caller which template or which value was at fault.

## Decision

### The engine owns its `Configuration`

`NotificationTemplateEngine` builds one `freemarker.template.Configuration` per engine instance and never shares it.
FreeMarker becomes a direct dependency of `notification-core`, at the version the Spring Boot BOM manages, which is also the version persistence-utils is built against (2.3.34).

Settings that keep existing templates rendering byte for byte as before:

| Setting | Value | Same as persistence-utils |
|---------|-------|---------------------------|
| `incompatible_improvements` | 2.3.23 | yes |
| `default_encoding` | UTF-8 | yes |
| `locale` | `Locale.US` | yes |
| `number_format` | `computer` | yes |
| object wrapper, date formats, time zone, auto-escaping policy | FreeMarker defaults for 2.3.23 | yes |

The top-level template is still loaded and cached by the engine (the section markers are replaced with per-render tokens before parsing, so the parsed template cannot be cached) and is parsed with its path relative to the base path as its name, for example `acme/email/WELCOME.ftl`.
`NotificationTemplateEngineEquivalenceTest` renders representative templates through both paths and compares the whole `RenderedContent`: interpolation, built-ins, number and date formats, lists, macros, functions, every notification helper, `trim`, `eval`, `serde_serialize`, HTML email with auto-escape off and on, `escapeHtml`, `?no_esc` and `[TEXT]` sections.

### Includes resolve against the base path

A `BasePathTemplateLoader` reads included templates through the same `ResourceLoader` and base path as top-level templates, so `classpath:` and `file:` base paths both work.

- A relative name resolves against the including template: `<#include "parts/footer.ftl">` in `acme/email/WELCOME.ftl` loads `acme/email/parts/footer.ftl`.
- A name starting with `/` resolves from the base path: `<#import "/shared/macros.ftl" as m>`.
- The template name format is `DEFAULT_2_4_0`, which rejects backslashes and refuses `..` that backs out of the root.
- Every segment of the resolved name must pass the 1.1.2 id rule (`^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`, no `..`); anything else, such as `file://...`, `classpath:...`, `%2F` or a hidden file, is treated as not found and logged at WARN with a masked prefix.
- Localized lookup is off, so `footer.ftl` is looked up once rather than as `footer_en_US.ftl`, `footer_en.ftl` and `footer.ftl`.
- The tenant-then-default lookup applies to top-level templates as before.
  An include resolves from the directory of the file that was actually chosen, so a tenant template that wants a default partial names it (`/default/email/footer.ftl`).

### Errors are reported once, as `TemplateRenderException`

- The template exception handler has RETHROW semantics: it records which data-model value failed (next section) and rethrows the exception unchanged, writing nothing to the output.
- `log_template_exceptions` is `false`, so FreeMarker itself never logs.
- `wrap_unchecked_exceptions` is `true`, so a helper that throws (an unknown currency code, an invalid zone) becomes a template error with a line and column.
- The engine throws `TemplateRenderException` (DD-27) with a reason:
  - `INVALID_TEMPLATE` for a syntax error, or an include or import that is missing, badly named or invalid;
  - `MODEL_INVALID` for any other processing error: a missing or null value, a wrong type, a failing helper;
  - `NOT_FOUND` stays `TemplateNotFoundException`, thrown where it always was.
- The message is FreeMarker's description on one line, without its tips and without the FTL stack trace, with the template name, line and column, for example `Template WELCOME for tenant acme could not be rendered: The following has evaluated to null or missing: ==> user.name [in template "default/email/WELCOME.ftl" at line 3, column 9]`.
- The engine does not log.
  The layer that decides the outcome logs once, without a stack trace: the send path's existing failure line when the failure becomes a `FAILED` response, or the REST and Kafka entry points at WARN when the request is refused (DD-27).

### The variable path is rebased onto the data model

For a model error, `TemplateRenderException.getVariablePath()` names the value that broke the render.

- FreeMarker blames an expression such as `u.name`.
  When `u` is a `#function` or `#macro` parameter, that is not a name the caller knows.
- The exception handler runs while the failing macro context is still active.
  It parses the blamed expression as a plain path (`a.b[0]["c d"]`), evaluates it step by step from the current environment, and looks for each step's value, by identity, in the data model of this render.
- The deepest step found is replaced by its data-model path, so `<#macro card u>${u.name}</#macro><@card u=user/>` reports `user.name`, and an item of `<#list user.items as item>` passed to a macro reports `user.items[1].sku`.
- Only values with an identity worth matching are searched for (maps, lists, arrays, beans), never strings, numbers, booleans or dates.
  The search runs only on failure and is bounded in depth (16) and nodes (10,000).
- The path is `null` when it cannot be determined: the blamed expression is not a plain path, it starts at a value the template created itself, a scalar was passed into a macro, or FreeMarker blames no expression (a null argument for a `#function` parameter without a default).

### Helpers and `notification.template.legacy-helpers`

- The notification helpers (`formatDate`, `formatDateTime`, `formatCurrency`, `truncate`, `capitalize`, `escapeHtml`, `defaultValue`, `urlEncode`) are unchanged.
- `uuid` and `trim` are always added; they only compute a value from their arguments.
- `notification.template.legacy-helpers` (default `true` in 1.3, deprecated, default `false` from 2.0):
  - `true` adds `eval`, `file`, `js`, `serde_deserializeToMap` and `serde_serialize`, delegating to the same persistence-utils functions as before (`TemplateEngine.generate`, `FileUtils`, `ScriptEngines`, `SerDe`), and leaves `?new` unrestricted; the engine logs one WARN at startup naming the helpers;
  - `false` adds none of them and restricts `?new` with FreeMarker's `SAFER_RESOLVER`, which refuses `Execute`, `ObjectConstructor` and `JythonRuntime`.
- As before, the helpers replace data-model entries of the same name; with `false`, a data entry named `file` or `eval` is data.

### Cache properties

`cache-enabled`, `cache-ttl-seconds` and `cache-max-size` keep governing the top-level cache, and now also FreeMarker's cache of included templates: disabled means no caching, the TTL becomes the update delay (never re-checked when 0 or negative), and the size bounds the number of cached includes.
`clearCache(tenant)` and `clearAllCache()` (and `POST /api/v1/admin/cache/templates/clear`) also clear the include cache, all of it, because includes are shared between tenants.

## Reasoning

### Why own the configuration instead of changing persistence-utils

The persistence-utils engine is a process-wide singleton used by other code, so any setting changed there changes it for them too.
Its loaders know nothing of `base-path`, and the helpers are part of its contract.
A configuration per engine is the only way to make these choices for notifications alone.

### Why keep incompatible improvements at 2.3.23

Raising it changes output: from 2.3.24 the `.ftlh` and `.ftlx` extensions switch auto-escaping on, and from 2.3.32 `?c` and the `computer` number format print some numbers differently.
The point of this change is that nobody's templates render differently, which the equivalence test pins down.
A newer level can come with 2.0, when the defaults move anyway.

### Why a recording handler rather than the plain RETHROW handler

FreeMarker unwinds the macro context before the exception reaches the caller.
Only the exception handler sees the environment at the moment of failure, with the function or macro parameters still bound, which is what the rebasing needs.
It rethrows the same exception, so the semantics are RETHROW's.

### Why identity matching rather than parsing the FTL stack

The FTL stack is text meant for people.
Mapping a parameter back to the caller's argument would mean parsing positional and named argument lists out of it, for macros, functions and nested calls.
Identity matching uses the values themselves and handles any nesting depth, lists and beans, with one well-defined limit: scalars cannot be traced.

### Why WARN at the entry points rather than in the engine

A render failure is the caller's input or the template author's mistake, not a fault of the service, so it is not an ERROR with a stack trace.
The engine does not know whether the failure will be refused (REST 422, Kafka rejection) or stored as a `FAILED` attempt, so it leaves the one log line to the layer that knows.

### Why `uuid` and `trim` stay

Neither reads anything outside its arguments or runs code, and templates in the wild use both.
Removing them would break templates for no safety gain.

### Why `?new` follows `legacy-helpers`

`?new` with `Execute` is the same capability as `js`: running code from a template.
Restricting it while leaving `js` would protect nothing, and restricting it with the helpers on would break templates the flag promises to keep working.
`SAFER_RESOLVER` still lets a template instantiate its own `TemplateModel` classes.

### Why includes do not fall back from the tenant to `default/`

FreeMarker resolves includes relative to the including file, which is the behaviour template authors know.
A hidden fallback would make the same `<#include>` load different files depending on which tenant directories exist.

## Consequences

### Positive

- `<#include>` and `<#import>` work for `classpath:` and `file:` base paths, relative to the including template, and cannot leave the base path.
- FreeMarker no longer logs render failures with stack traces, and nothing the application or other libraries do to the persistence-utils configuration affects notification templates.
- Callers get a typed exception with a reason and, for model errors, the data-model path.
- Deployments can switch off the file-reading and code-running helpers now, and will be safe by default in 2.0.

### Negative

- With `legacy-helpers=true`, `eval` and `file` still render through the persistence-utils configuration, so errors inside them are still logged there.
- `.current_template_name` and `.main_template_name` now return the template path instead of an empty string.
- A render failure sent with `validate-before-dispatch=false` is reported with `TEMPLATE_INVALID` or `TEMPLATE_MODEL_INVALID` instead of `INTERNAL_ERROR`, and its DLQ entry is `PERMANENT` instead of `UNKNOWN`.
- Templates that included files from persistence-utils' own locations (`classpath:/templates/` or `./templates` while `base-path` points elsewhere) must move them under `base-path`.
- The variable path is `null` in the cases listed above.

## Related Decisions

- [04-template-engine.md](./04-template-engine.md) - FreeMarker through persistence-utils, the tenant-then-default resolution.
- [27-validate-before-dispatch.md](./27-validate-before-dispatch.md) - `TemplateRenderException` on the send path, HTTP 422 and Kafka handling.
- [13-retries-and-dlq.md](./13-retries-and-dlq.md) - failure types recorded in the dead-letter store.
- [22-metrics.md](./22-metrics.md) - the meter catalogue.
