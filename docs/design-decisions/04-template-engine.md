# Decision 04: Template Engine

## Status: DECIDED

## Context
Notifications require dynamic content generation from templates. Need to:
1. Use FreeMarker (consistent with existing codebase)
2. Support tenant-specific templates
3. Leverage existing `TemplateEngine` from `persistence-utils`
4. Add notification-specific helpers without modifying core library

## Decision
Create a **wrapper class** `NotificationTemplateEngine` that extends functionality of `persistence-utils` `TemplateEngine` without modifying the core library.

### Wrapper Implementation

```java
package com.lazydevs.notification.core.template;

import lazydevs.mapper.utils.engine.TemplateEngine;
import lazydevs.persistence.connection.multitenant.TenantContext;

public class NotificationTemplateEngine {

    private final TemplateEngine coreEngine = TemplateEngine.getInstance();
    private final TemplateRepository templateRepository;
    private final NotificationProperties properties;

    // Cache: tenantId -> templateKey -> compiled template content
    private final Map<String, Map<String, String>> templateCache = new ConcurrentHashMap<>();

    /**
     * Generate content from template with tenant awareness
     */
    public String generate(String channel, String notificationType, Map<String, Object> data) {
        String tenantId = TenantContext.getTenantId();
        String templateContent = resolveTemplate(tenantId, channel, notificationType);

        // Enrich data with helper functions
        Map<String, Object> enrichedData = enrichWithHelpers(data);

        return coreEngine.generate(templateContent, enrichedData);
    }

    /**
     * Template resolution order:
     * 1. Database (tenant-specific override)
     * 2. Classpath: templates/{tenantId}/{channel}/{notificationType}.ftl
     * 3. Classpath: templates/default/{channel}/{notificationType}.ftl
     */
    private String resolveTemplate(String tenantId, String channel, String notificationType) {
        String cacheKey = channel + "/" + notificationType;

        return templateCache
            .computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(cacheKey, k -> loadTemplate(tenantId, channel, notificationType));
    }

    /**
     * Add notification-specific helper functions
     */
    private Map<String, Object> enrichWithHelpers(Map<String, Object> data) {
        Map<String, Object> enriched = new HashMap<>(data);

        // Date/Time formatting
        enriched.put("formatDate", new FormatDateMethod());
        enriched.put("formatDateTime", new FormatDateTimeMethod());

        // Currency formatting
        enriched.put("formatCurrency", new FormatCurrencyMethod());

        // String utilities
        enriched.put("truncate", new TruncateMethod());
        enriched.put("capitalize", new CapitalizeMethod());
        enriched.put("escapeHtml", new EscapeHtmlMethod());

        // Default value helper
        enriched.put("defaultValue", new DefaultValueMethod());

        return enriched;
    }

    /**
     * Validate template syntax
     */
    public TemplateValidationResult validate(String templateContent) {
        try {
            coreEngine.generate(templateContent, Collections.emptyMap());
            return TemplateValidationResult.valid();
        } catch (Exception e) {
            return TemplateValidationResult.invalid(e.getMessage());
        }
    }

    /**
     * Clear cache for tenant (useful after template update)
     */
    public void clearCache(String tenantId) {
        templateCache.remove(tenantId);
    }

    public void clearAllCache() {
        templateCache.clear();
    }
}
```

### Helper Methods (FreeMarker TemplateMethodModelEx)

```java
// Example: ${formatDate(orderDate, 'yyyy-MM-dd')}
public class FormatDateMethod implements TemplateMethodModelEx {
    @Override
    public Object exec(List arguments) throws TemplateModelException {
        if (arguments.size() < 2) {
            throw new TemplateModelException("formatDate requires date and pattern");
        }
        Object dateObj = DeepUnwrap.unwrap((TemplateModel) arguments.get(0));
        String pattern = arguments.get(1).toString();

        if (dateObj instanceof Date) {
            return new SimpleDateFormat(pattern).format((Date) dateObj);
        } else if (dateObj instanceof Instant) {
            return DateTimeFormatter.ofPattern(pattern)
                .withZone(ZoneId.systemDefault())
                .format((Instant) dateObj);
        }
        return dateObj.toString();
    }
}

// Example: ${truncate(description, 100)}
public class TruncateMethod implements TemplateMethodModelEx {
    @Override
    public Object exec(List arguments) throws TemplateModelException {
        String text = arguments.get(0).toString();
        int maxLength = Integer.parseInt(arguments.get(1).toString());

        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
}

// Example: ${escapeHtml(userInput)}
public class EscapeHtmlMethod implements TemplateMethodModelEx {
    @Override
    public Object exec(List arguments) throws TemplateModelException {
        String text = arguments.get(0).toString();
        return StringEscapeUtils.escapeHtml4(text);
    }
}

// Example: ${defaultValue(nullableField, 'N/A')}
public class DefaultValueMethod implements TemplateMethodModelEx {
    @Override
    public Object exec(List arguments) throws TemplateModelException {
        Object value = DeepUnwrap.unwrap((TemplateModel) arguments.get(0));
        String defaultVal = arguments.get(1).toString();

        if (value == null || (value instanceof String && ((String) value).isBlank())) {
            return defaultVal;
        }
        return value;
    }
}
```

### Template Directory Structure

```
resources/
└── templates/
    ├── default/                          # Fallback templates
    │   ├── email/
    │   │   ├── ORDER_CONFIRMATION.ftl
    │   │   ├── PASSWORD_RESET.ftl
    │   │   └── WELCOME.ftl
    │   ├── sms/
    │   │   ├── OTP.ftl
    │   │   └── ORDER_SHIPPED.ftl
    │   └── whatsapp/
    │       └── ORDER_STATUS.ftl
    └── tenant-a/                         # Tenant-specific overrides
        └── email/
            └── ORDER_CONFIRMATION.ftl    # Custom template for tenant-a
```

### Example Template

```ftl
<#-- templates/default/email/ORDER_CONFIRMATION.ftl -->
<!DOCTYPE html>
<html>
<head>
    <title>Order Confirmation</title>
</head>
<body>
    <h1>Thank you for your order, ${customerName}!</h1>

    <p>Your order <strong>#${orderId}</strong> has been confirmed.</p>

    <table>
        <tr>
            <th>Item</th>
            <th>Quantity</th>
            <th>Price</th>
        </tr>
        <#list items as item>
        <tr>
            <td>${escapeHtml(item.name)}</td>
            <td>${item.qty}</td>
            <td>${formatCurrency(item.price, 'USD')}</td>
        </tr>
        </#list>
    </table>

    <p><strong>Total: ${formatCurrency(total, 'USD')}</strong></p>

    <p>Expected delivery: ${formatDate(deliveryDate, 'MMMM dd, yyyy')}</p>

    <p>Questions? Contact us at ${defaultValue(supportEmail, 'support@example.com')}</p>
</body>
</html>
```

## Reasoning

### Why Wrapper (Option A) over Modifying Core:
1. **No changes to `persistence-utils`**: Avoid breaking other projects
2. **Notification-specific concerns**: Tenant-aware loading, caching, helpers
3. **Single Responsibility**: Core engine handles FreeMarker, wrapper handles notification context
4. **Easier testing**: Can mock the wrapper without affecting core

### Why Add Helper Methods:
1. **Common notification needs**: Date formatting, currency, truncation
2. **Security**: HTML escaping for user-provided data
3. **Convenience**: Cleaner templates, less logic in templates

### Why Hybrid Template Storage:
1. **Classpath default**: Standard templates ship with application
2. **Tenant override in classpath**: For known tenants at deploy time
3. **Database (future)**: For runtime tenant template management

## Alternatives Considered

### Alternative 1: Modify `persistence-utils` TemplateEngine
- **Rejected**: Would affect all consumers of the library

### Alternative 2: Use Thymeleaf instead
- **Rejected**: FreeMarker already in use, Thymeleaf heavier

### Alternative 3: No wrapper, use core directly
- **Rejected**: Loses tenant-awareness and notification-specific helpers

## Consequences

### Positive
- Core library unchanged
- Clean separation of concerns
- Extensible helper system
- Tenant-aware template resolution

### Negative
- Additional abstraction layer
- Need to maintain helper methods

## Amendment: 1.1.2

The wrapper sketch above predates the shipped code.
1.1.2 fixes several defects in the shipped `NotificationTemplateEngine` and adds three opt-in properties.

### Path resolution

Templates resolve relative to `notification.template.base-path` as `{base-path}{tenantId}/{channel}/{id}.ftl`, then `{base-path}default/{channel}/{id}.ftl`.
Up to 1.1.1 the engine prefixed an extra `templates/` segment, so with the default base path it looked under `classpath:/templates/templates/` and the shipped defaults were never found.
For one release each lookup falls back to the old location after the new one (tenant new, tenant old, default new, default old), so a tenant override keeps precedence over the default whichever layout it uses.
A file found only at the old location logs one WARN naming the file; the fallback is removed in 1.2.

### Section markers

The engine used to find `[SUBJECT]`, `[/SUBJECT]`, `[BODY]` and `[/BODY]` in the rendered output, so template data containing a marker could replace the body or cut the subject short.
It now replaces the markers in the template source with tokens built from a fresh random UUID (`\u0000<MARKER>:<uuid>\u0000`) before rendering and splits the output on those tokens.
Data cannot contain a token it has never seen, so a marker in data is plain text.
Tokens left over from unpaired markers are turned back into the literal markers, so such templates render exactly as before.
Marker replacement applies to email templates only; other channels never parsed sections.

### Text part

An optional `[TEXT]...[/TEXT]` section is a plain-text alternative.
When present, the body becomes the HTML part and the section the text part of a multipart message; when absent, nothing changes.
Deriving a text part from the HTML automatically is planned for a later release.
HTML detection (used when there is no `[TEXT]` section) now also recognises `<table`, `<br`, `<span`, `<a ` and `<!DOCTYPE`, case-insensitively.

### Cache

The per-tenant `ConcurrentHashMap` was unbounded and never expired, and `cache-ttl-seconds` was bound but never read.
The cache is now a Caffeine cache keyed by tenant and template, with `expireAfterWrite(cache-ttl-seconds)` (0 or negative: no expiry) and `maximumSize(cache-max-size)` (default 1000; 0 or negative: unbounded).
A missing template is not cached, and the admin clear endpoints still clear one tenant or all tenants.

### Auto-escaping

`notification.template.auto-escape` (default `false`) HTML-escapes interpolated values in HTML email bodies.
The persistence-utils `TemplateEngine` renders every template through one shared FreeMarker `Configuration` (incompatible improvements 2.3.23, undefined output format), so the output format cannot be set per render through it.
Instead, with the flag on, the engine wraps the body section of the template source in `<#outputformat "HTML">...</#outputformat>`, leaving the subject and any `[TEXT]` section outside it.
`escapeHtml()` then returns `TemplateHTMLOutputModel` markup, which the HTML output format prints without escaping again; in the subject and text part the undefined output format prints the same markup text as before.
`?no_esc` prints trusted markup as is.
With the flag on, whether the body is HTML is decided from its template source, not from the rendered output, so data cannot turn a plain-text body into an HTML one and plain-text bodies are not entity-escaped.
A body with a `[TEXT]` section is always the HTML part, so it is always escaped.
With the flag off, output is byte-identical to 1.1.1 apart from the bug fixes in this amendment.
Trade-offs of the source wrapping: a directive that opens inside the body section and closes outside it becomes a parse error with the flag on, and string built-ins cannot be chained directly after `escapeHtml()`.

### Helpers

- `escapeHtml`, `truncate`, `capitalize` and `urlEncode` print a null value as an empty string instead of `null`.
- `truncate` with a limit of 3 or less cuts without an ellipsis instead of throwing.
- `formatDate` and `formatDateTime` accept any `TemporalAccessor`, `java.util.Date`, epoch milliseconds and ISO-8601 strings (tried as date-time, instant, then date); anything else, and a value that lacks a field the pattern needs, is printed unchanged.
- `formatDate(value, pattern[, zone[, locale]])`, `formatDateTime(value, pattern[, zone[, locale]])` and `formatCurrency(amount, code[, locale])` take optional zone ids and BCP-47 locale tags.

### Known limitations

- The defaults stay as they were for compatibility: `formatDate` uses the JVM zone, `formatDateTime` uses UTC, and only `formatCurrency` uses a fixed locale (`en-US`).
  They will be unified in 1.2.
- persistence-utils injects `file`, `eval` and `js` helpers into every template model, and `file` reads any path the process can read.
  Template authoring is therefore a trusted capability; the helpers go away when this engine owns its FreeMarker configuration in 1.2.
- `<#include>` and `<#import>` resolve against persistence-utils' own loaders (`classpath:/templates/` and `./templates`), not relative to `base-path`.

## Related Decisions
- [03-multi-tenancy.md](./03-multi-tenancy.md) - Tenant context used for template resolution
