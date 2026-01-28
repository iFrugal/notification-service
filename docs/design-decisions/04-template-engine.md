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

## Related Decisions
- [03-multi-tenancy.md](./03-multi-tenancy.md) - Tenant context used for template resolution
