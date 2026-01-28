package com.lazydevs.notification.core.template;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.TemplateNotFoundException;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.core.config.NotificationProperties;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;
import lazydevs.mapper.utils.engine.TemplateEngine;
import lazydevs.persistence.connection.multitenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringEscapeUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Notification-aware template engine wrapper.
 * Wraps persistence-utils TemplateEngine with:
 * - Tenant-aware template resolution
 * - Template caching
 * - Notification-specific helper methods
 */
@Slf4j
@Component
public class NotificationTemplateEngine {

    private final TemplateEngine coreEngine = TemplateEngine.getInstance();
    private final NotificationProperties properties;
    private final ResourceLoader resourceLoader;

    /**
     * Template cache: tenantId -> channel/templateKey -> template content
     */
    private final Map<String, Map<String, String>> templateCache = new ConcurrentHashMap<>();

    public NotificationTemplateEngine(NotificationProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    /**
     * Render a notification template.
     *
     * @param request the notification request
     * @return the rendered content
     */
    public RenderedContent render(NotificationRequest request) {
        String tenantId = request.getTenantId() != null ? request.getTenantId() : TenantContext.getTenantId();
        if (tenantId == null) {
            tenantId = properties.getDefaultTenant();
        }

        Channel channel = request.getChannel();
        String templateId = request.getTemplateId() != null
                ? request.getTemplateId()
                : request.getNotificationType();

        // Resolve and load template
        String templateContent = resolveTemplate(tenantId, channel, templateId);

        // Enrich data with helper functions
        Map<String, Object> enrichedData = enrichWithHelpers(request.getTemplateData());

        // Generate content using core engine
        String renderedContent = coreEngine.generate(templateContent, enrichedData);

        // Parse the rendered content based on channel
        return parseRenderedContent(channel, renderedContent, templateId);
    }

    /**
     * Resolve template content with fallback logic.
     * Resolution order:
     * 1. templates/{tenantId}/{channel}/{templateId}.ftl
     * 2. templates/default/{channel}/{templateId}.ftl
     */
    private String resolveTemplate(String tenantId, Channel channel, String templateId) {
        String cacheKey = channel.name().toLowerCase() + "/" + templateId;

        // Check cache
        if (properties.getTemplate().isCacheEnabled()) {
            Map<String, String> tenantCache = templateCache.get(tenantId);
            if (tenantCache != null && tenantCache.containsKey(cacheKey)) {
                return tenantCache.get(cacheKey);
            }
        }

        // Try tenant-specific template
        String tenantPath = String.format("templates/%s/%s/%s.ftl",
                tenantId, channel.name().toLowerCase(), templateId);
        String content = loadTemplate(tenantPath);

        // Fallback to default
        if (content == null) {
            String defaultPath = String.format("templates/default/%s/%s.ftl",
                    channel.name().toLowerCase(), templateId);
            content = loadTemplate(defaultPath);
        }

        if (content == null) {
            throw new TemplateNotFoundException(tenantId, channel.name().toLowerCase(), templateId);
        }

        // Cache the content
        if (properties.getTemplate().isCacheEnabled()) {
            templateCache
                    .computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>())
                    .put(cacheKey, content);
        }

        return content;
    }

    private String loadTemplate(String path) {
        try {
            String fullPath = properties.getTemplate().getBasePath() + path;
            Resource resource = resourceLoader.getResource(fullPath);
            if (resource.exists()) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                }
            }
        } catch (Exception e) {
            log.debug("Failed to load template from {}: {}", path, e.getMessage());
        }
        return null;
    }

    /**
     * Parse rendered content based on channel.
     * Supports special markers for subject/body separation.
     */
    private RenderedContent parseRenderedContent(Channel channel, String content, String templateId) {
        if (channel == Channel.EMAIL) {
            return parseEmailContent(content, templateId);
        } else {
            return RenderedContent.text(content.trim());
        }
    }

    /**
     * Parse email template with subject/body sections.
     * Template format:
     * [SUBJECT]
     * Email subject here
     * [/SUBJECT]
     * [BODY]
     * Email body here (HTML or text)
     * [/BODY]
     *
     * Or just plain content (treated as body)
     */
    private RenderedContent parseEmailContent(String content, String templateId) {
        String subject = null;
        String body = content;

        // Try to extract subject
        int subjectStart = content.indexOf("[SUBJECT]");
        int subjectEnd = content.indexOf("[/SUBJECT]");
        if (subjectStart >= 0 && subjectEnd > subjectStart) {
            subject = content.substring(subjectStart + 9, subjectEnd).trim();
        }

        // Try to extract body
        int bodyStart = content.indexOf("[BODY]");
        int bodyEnd = content.indexOf("[/BODY]");
        if (bodyStart >= 0 && bodyEnd > bodyStart) {
            body = content.substring(bodyStart + 6, bodyEnd).trim();
        } else if (subjectEnd > 0) {
            // If no [BODY] tag but has subject, treat rest as body
            body = content.substring(subjectEnd + 10).trim();
        }

        // Determine if HTML
        boolean isHtml = body.contains("<html") || body.contains("<HTML") ||
                body.contains("<body") || body.contains("<BODY") ||
                body.contains("<div") || body.contains("<p>");

        return RenderedContent.builder()
                .subject(subject)
                .htmlBody(isHtml ? body : null)
                .textBody(isHtml ? null : body)
                .templateId(templateId)
                .build();
    }

    /**
     * Add notification-specific helper functions to template data.
     */
    private Map<String, Object> enrichWithHelpers(Map<String, Object> data) {
        Map<String, Object> enriched = data != null ? new HashMap<>(data) : new HashMap<>();

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

        // URL encoding
        enriched.put("urlEncode", new UrlEncodeMethod());

        return enriched;
    }

    /**
     * Clear template cache for a tenant.
     */
    public void clearCache(String tenantId) {
        templateCache.remove(tenantId);
        log.debug("Cleared template cache for tenant: {}", tenantId);
    }

    /**
     * Clear all template caches.
     */
    public void clearAllCache() {
        templateCache.clear();
        log.debug("Cleared all template caches");
    }

    // ========== Helper Method Implementations ==========

    /**
     * Format date: ${formatDate(date, 'yyyy-MM-dd')}
     */
    private static class FormatDateMethod implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) throws TemplateModelException {
            if (arguments.size() < 2) {
                throw new TemplateModelException("formatDate requires date and pattern arguments");
            }
            Object dateObj = unwrap(arguments.get(0));
            String pattern = String.valueOf(arguments.get(1));

            if (dateObj == null) return "";

            if (dateObj instanceof Date) {
                return new SimpleDateFormat(pattern).format((Date) dateObj);
            } else if (dateObj instanceof Instant) {
                return DateTimeFormatter.ofPattern(pattern)
                        .withZone(ZoneId.systemDefault())
                        .format((Instant) dateObj);
            } else if (dateObj instanceof java.time.LocalDate) {
                return ((java.time.LocalDate) dateObj).format(DateTimeFormatter.ofPattern(pattern));
            }
            return String.valueOf(dateObj);
        }
    }

    /**
     * Format datetime: ${formatDateTime(instant, 'yyyy-MM-dd HH:mm:ss', 'America/New_York')}
     */
    private static class FormatDateTimeMethod implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) throws TemplateModelException {
            if (arguments.size() < 2) {
                throw new TemplateModelException("formatDateTime requires instant and pattern arguments");
            }
            Object dateObj = unwrap(arguments.get(0));
            String pattern = String.valueOf(arguments.get(1));
            String timezone = arguments.size() > 2 ? String.valueOf(arguments.get(2)) : "UTC";

            if (dateObj == null) return "";

            ZoneId zoneId = ZoneId.of(timezone);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern).withZone(zoneId);

            if (dateObj instanceof Instant) {
                return formatter.format((Instant) dateObj);
            } else if (dateObj instanceof Date) {
                return formatter.format(((Date) dateObj).toInstant());
            }
            return String.valueOf(dateObj);
        }
    }

    /**
     * Format currency: ${formatCurrency(amount, 'USD')}
     */
    private static class FormatCurrencyMethod implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) throws TemplateModelException {
            if (arguments.size() < 2) {
                throw new TemplateModelException("formatCurrency requires amount and currency code");
            }
            Object amountObj = unwrap(arguments.get(0));
            String currencyCode = String.valueOf(arguments.get(1));

            if (amountObj == null) return "";

            double amount = amountObj instanceof Number
                    ? ((Number) amountObj).doubleValue()
                    : Double.parseDouble(String.valueOf(amountObj));

            NumberFormat formatter = NumberFormat.getCurrencyInstance(Locale.US);
            formatter.setCurrency(Currency.getInstance(currencyCode));
            return formatter.format(amount);
        }
    }

    /**
     * Truncate text: ${truncate(text, 100)}
     */
    private static class TruncateMethod implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) throws TemplateModelException {
            if (arguments.size() < 2) {
                throw new TemplateModelException("truncate requires text and maxLength");
            }
            String text = String.valueOf(unwrap(arguments.get(0)));
            int maxLength = Integer.parseInt(String.valueOf(arguments.get(1)));

            if (text == null || text.length() <= maxLength) {
                return text;
            }
            return text.substring(0, maxLength - 3) + "...";
        }
    }

    /**
     * Capitalize: ${capitalize(text)}
     */
    private static class CapitalizeMethod implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) throws TemplateModelException {
            if (arguments.isEmpty()) return "";
            String text = String.valueOf(unwrap(arguments.get(0)));
            if (text == null || text.isEmpty()) return text;
            return text.substring(0, 1).toUpperCase() + text.substring(1).toLowerCase();
        }
    }

    /**
     * Escape HTML: ${escapeHtml(userInput)}
     */
    private static class EscapeHtmlMethod implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) throws TemplateModelException {
            if (arguments.isEmpty()) return "";
            String text = String.valueOf(unwrap(arguments.get(0)));
            return StringEscapeUtils.escapeHtml4(text);
        }
    }

    /**
     * Default value: ${defaultValue(nullableField, 'N/A')}
     */
    private static class DefaultValueMethod implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) throws TemplateModelException {
            if (arguments.size() < 2) {
                throw new TemplateModelException("defaultValue requires value and default");
            }
            Object value = unwrap(arguments.get(0));
            String defaultVal = String.valueOf(arguments.get(1));

            if (value == null || (value instanceof String && ((String) value).isBlank())) {
                return defaultVal;
            }
            return value;
        }
    }

    /**
     * URL encode: ${urlEncode(text)}
     */
    private static class UrlEncodeMethod implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) throws TemplateModelException {
            if (arguments.isEmpty()) return "";
            String text = String.valueOf(unwrap(arguments.get(0)));
            try {
                return java.net.URLEncoder.encode(text, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return text;
            }
        }
    }

    /**
     * Unwrap FreeMarker template model to Java object.
     */
    private static Object unwrap(Object obj) {
        if (obj instanceof freemarker.template.TemplateModel) {
            try {
                return freemarker.template.utility.DeepUnwrap.unwrap((freemarker.template.TemplateModel) obj);
            } catch (Exception e) {
                return obj;
            }
        }
        return obj;
    }
}
