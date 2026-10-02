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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
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
public class NotificationTemplateEngine {

    /** Tenant directory holding the fallback templates. */
    private static final String DEFAULT_TENANT_DIR = "default";

    /**
     * Extra path segment that 1.1.1 and earlier inserted under the base path.
     * Still tried after the correct location for one release; remove in 1.2.
     */
    private static final String LEGACY_SEGMENT = "templates/";

    private final TemplateEngine coreEngine = TemplateEngine.getInstance();
    private final NotificationProperties properties;
    private final ResourceLoader resourceLoader;

    /**
     * Template cache: tenantId -> channel/templateKey -> template content
     */
    private final Map<String, Map<String, String>> templateCache = new ConcurrentHashMap<>();

    /** Legacy template locations already warned about, so each is logged once. */
    private final Set<String> warnedLegacyPaths = ConcurrentHashMap.newKeySet();

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

        // Swap the section markers in the template source for per-render random tokens,
        // so a marker that arrives in template data is plain text and cannot move a section.
        SectionMarkers markers = channel == Channel.EMAIL ? SectionMarkers.random() : null;
        String source = markers != null ? markers.tokenize(templateContent) : templateContent;

        // Generate content using core engine
        String renderedContent = coreEngine.generate(source, enrichedData);

        // Parse the rendered content based on channel
        return parseRenderedContent(channel, renderedContent, templateId, markers);
    }

    /**
     * Resolve template content with fallback logic.
     * Paths are relative to {@code notification.template.base-path}
     * (default {@code classpath:/templates/}).
     * Resolution order:
     * 1. {basePath}{tenantId}/{channel}/{templateId}.ftl
     * 2. {basePath}templates/{tenantId}/{channel}/{templateId}.ftl (pre-1.1.2 location, logs a WARN)
     * 3. {basePath}default/{channel}/{templateId}.ftl
     * 4. {basePath}templates/default/{channel}/{templateId}.ftl (pre-1.1.2 location, logs a WARN)
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
        String tenantPath = String.format("%s/%s/%s.ftl",
                tenantId, channel.name().toLowerCase(), templateId);
        String content = loadTemplateWithLegacyFallback(tenantPath);

        // Fallback to default
        if (content == null) {
            String defaultPath = String.format("%s/%s/%s.ftl",
                    DEFAULT_TENANT_DIR, channel.name().toLowerCase(), templateId);
            content = loadTemplateWithLegacyFallback(defaultPath);
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

    /**
     * Load {@code path} relative to the base path. If it is missing, try the location
     * 1.1.1 and earlier used by mistake ({@code <basePath>templates/<path>}) so custom
     * layouts that relied on it keep working for one release, and warn once per file.
     */
    private String loadTemplateWithLegacyFallback(String path) {
        String content = loadTemplate(path);
        if (content == null) {
            String legacyPath = LEGACY_SEGMENT + path;
            content = loadTemplate(legacyPath);
            if (content != null && warnedLegacyPaths.add(legacyPath)) {
                String basePath = basePath();
                log.warn("Template {}{} was loaded from its pre-1.1.2 location, which repeats 'templates/' "
                                + "under notification.template.base-path={}. Move it to {}{} or set the base path "
                                + "to {}{}; this fallback will be removed in 1.2.",
                        basePath, legacyPath, basePath, basePath, path, basePath, LEGACY_SEGMENT);
            }
        }
        return content;
    }

    /**
     * The configured base path, with a trailing slash so relative paths append cleanly.
     */
    private String basePath() {
        String basePath = properties.getTemplate().getBasePath();
        if (basePath == null) {
            return "";
        }
        return basePath.isEmpty() || basePath.endsWith("/") ? basePath : basePath + "/";
    }

    private String loadTemplate(String path) {
        try {
            String fullPath = basePath() + path;
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
    private RenderedContent parseRenderedContent(Channel channel, String content, String templateId,
                                                 SectionMarkers markers) {
        if (channel == Channel.EMAIL) {
            return parseEmailContent(content, templateId, markers);
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
     *
     * The markers were swapped for the per-render tokens in {@code markers} before
     * rendering, so only markers written in the template source delimit sections.
     */
    private RenderedContent parseEmailContent(String content, String templateId, SectionMarkers markers) {
        String subject = null;
        String body = content;
        String subjectOpen = markers.token(Marker.SUBJECT_OPEN);
        String subjectClose = markers.token(Marker.SUBJECT_CLOSE);
        String bodyOpen = markers.token(Marker.BODY_OPEN);
        String bodyClose = markers.token(Marker.BODY_CLOSE);

        // Try to extract subject
        int subjectStart = content.indexOf(subjectOpen);
        int subjectEnd = content.indexOf(subjectClose);
        if (subjectStart >= 0 && subjectEnd > subjectStart) {
            subject = content.substring(subjectStart + subjectOpen.length(), subjectEnd).trim();
        }

        // Try to extract body
        int bodyStart = content.indexOf(bodyOpen);
        int bodyEnd = content.indexOf(bodyClose);
        if (bodyStart >= 0 && bodyEnd > bodyStart) {
            body = content.substring(bodyStart + bodyOpen.length(), bodyEnd).trim();
        } else if (subjectEnd > 0) {
            // If no [BODY] tag but has subject, treat rest as body
            body = content.substring(subjectEnd + subjectClose.length()).trim();
        }

        // Unpaired markers stay in the output as their literal text, as before
        subject = markers.restore(subject);
        body = markers.restore(body);

        // Determine if HTML
        boolean isHtml = body.contains("<html") || body.contains("<HTML") ||
                body.contains("<body") || body.contains("<BODY") ||
                body.contains("<div") || body.contains("<p>");

        return new RenderedContent(
                subject,
                isHtml ? null : body,
                isHtml ? body : null,
                templateId);
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
     * Format date: ${formatDate(date, 'yyyy-MM-dd')} or
     * ${formatDate(date, 'dd MMMM yyyy', 'Europe/Berlin', 'de-DE')}.
     * <p>
     * Accepts any {@link TemporalAccessor}, {@link Date}, epoch milliseconds and ISO-8601
     * strings (see {@link #toTemporal}); anything else is printed unchanged.
     * The optional zone (zone id) and locale (BCP-47 tag) default to the JVM's;
     * pass {@code ''} as the zone to set only the locale.
     */
    private static class FormatDateMethod implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) throws TemplateModelException {
            if (arguments.size() < 2) {
                throw new TemplateModelException("formatDate requires date and pattern arguments");
            }
            Object dateObj = unwrap(arguments.get(0));
            String pattern = String.valueOf(arguments.get(1));
            ZoneId zone = zoneArgument(arguments, 2);
            Locale locale = localeArgument(arguments, 3);

            if (dateObj == null) return "";

            if (dateObj instanceof Date d) {
                // SimpleDateFormat keeps the pattern semantics java.util.Date always had here
                SimpleDateFormat format = locale != null
                        ? new SimpleDateFormat(pattern, locale)
                        : new SimpleDateFormat(pattern);
                if (zone != null) {
                    format.setTimeZone(TimeZone.getTimeZone(zone));
                }
                return format.format(d);
            }
            return formatTemporal(dateObj, pattern, zone != null ? zone : ZoneId.systemDefault(),
                    zone != null, locale);
        }
    }

    /**
     * Format datetime: ${formatDateTime(instant, 'yyyy-MM-dd HH:mm:ss', 'America/New_York')} or
     * ${formatDateTime(instant, 'dd MMMM yyyy HH:mm', 'Europe/Berlin', 'de-DE')}.
     * <p>
     * Accepts the same values as {@code formatDate}.
     * The zone defaults to UTC (unlike {@code formatDate}, which uses the JVM zone; to be
     * unified in 1.2) and the locale to the JVM's.
     */
    private static class FormatDateTimeMethod implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) throws TemplateModelException {
            if (arguments.size() < 2) {
                throw new TemplateModelException("formatDateTime requires instant and pattern arguments");
            }
            Object dateObj = unwrap(arguments.get(0));
            String pattern = String.valueOf(arguments.get(1));
            ZoneId zone = zoneArgument(arguments, 2);
            Locale locale = localeArgument(arguments, 3);

            if (dateObj == null) return "";

            return formatTemporal(dateObj, pattern, zone != null ? zone : ZoneId.of("UTC"), zone != null, locale);
        }
    }

    /**
     * Format currency: ${formatCurrency(amount, 'USD')} or ${formatCurrency(amount, 'EUR', 'de-DE')}.
     * The optional locale (BCP-47 tag) defaults to en-US.
     */
    private static class FormatCurrencyMethod implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) throws TemplateModelException {
            if (arguments.size() < 2) {
                throw new TemplateModelException("formatCurrency requires amount and currency code");
            }
            Object amountObj = unwrap(arguments.get(0));
            String currencyCode = String.valueOf(arguments.get(1));
            Locale locale = localeArgument(arguments, 2);

            if (amountObj == null) return "";

            double amount = amountObj instanceof Number n
                    ? n.doubleValue()
                    : Double.parseDouble(String.valueOf(amountObj));

            NumberFormat formatter = NumberFormat.getCurrencyInstance(locale != null ? locale : Locale.US);
            formatter.setCurrency(Currency.getInstance(currencyCode));
            return formatter.format(amount);
        }
    }

    /**
     * Truncate text: ${truncate(text, 100)}
     * Appends "..." within the limit; a limit of 3 or less cuts without the ellipsis.
     */
    private static class TruncateMethod implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) throws TemplateModelException {
            if (arguments.size() < 2) {
                throw new TemplateModelException("truncate requires text and maxLength");
            }
            String text = textArgument(arguments.get(0));
            int maxLength = Integer.parseInt(String.valueOf(arguments.get(1)));

            if (text.length() <= maxLength) {
                return text;
            }
            if (maxLength <= 3) {
                return text.substring(0, Math.max(0, maxLength));
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
            String text = textArgument(arguments.get(0));
            if (text.isEmpty()) return text;
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
            String text = textArgument(arguments.get(0));
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

            if (value == null || (value instanceof String s && s.isBlank())) {
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
            String text = textArgument(arguments.get(0));
            try {
                return java.net.URLEncoder.encode(text, StandardCharsets.UTF_8);
            } catch (Exception _) {
                return text;
            }
        }
    }

    /**
     * Format a date-like value with a {@link DateTimeFormatter} pattern.
     * {@link Instant}-like values (Instant, Date, epoch millis) are shown in {@code zone};
     * values with their own offset or zone keep it unless {@code zoneGiven};
     * local dates and times are formatted as they are.
     * Values that are not dates, and dates that lack a field the pattern needs, are
     * printed unchanged rather than failing the render.
     */
    private static String formatTemporal(Object value, String pattern, ZoneId zone, boolean zoneGiven,
                                         Locale locale) {
        DateTimeFormatter formatter = locale != null
                ? DateTimeFormatter.ofPattern(pattern, locale)
                : DateTimeFormatter.ofPattern(pattern);
        TemporalAccessor temporal = toTemporal(value);
        if (temporal == null) {
            return String.valueOf(value);
        }
        if (temporal instanceof Instant || (zoneGiven && temporal.isSupported(ChronoField.INSTANT_SECONDS))) {
            formatter = formatter.withZone(zone);
        }
        try {
            return formatter.format(temporal);
        } catch (DateTimeException e) {
            log.debug("Cannot format {} with pattern '{}': {}", value, pattern, e.getMessage());
            return String.valueOf(value);
        }
    }

    /**
     * Convert a template value to a temporal: any {@link TemporalAccessor} as is,
     * {@link Date} and epoch milliseconds ({@link Number}) to an {@link Instant}, and
     * strings parsed as ISO-8601 date-time, then instant, then date.
     *
     * @return the temporal, or {@code null} if the value is not a recognisable date
     */
    static TemporalAccessor toTemporal(Object value) {
        if (value instanceof TemporalAccessor temporal) {
            return temporal;
        }
        if (value instanceof Date d) {
            // getTime(), not toInstant(): java.sql.Date.toInstant() throws
            return Instant.ofEpochMilli(d.getTime());
        }
        if (value instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue());
        }
        if (value instanceof CharSequence cs) {
            return parseIsoDate(cs.toString().trim());
        }
        return null;
    }

    private static TemporalAccessor parseIsoDate(String text) {
        try {
            return DateTimeFormatter.ISO_DATE_TIME.parseBest(text, ZonedDateTime::from, LocalDateTime::from);
        } catch (DateTimeParseException _) {
            // not a date-time, try the next form
        }
        try {
            return DateTimeFormatter.ISO_INSTANT.parse(text, Instant::from);
        } catch (DateTimeParseException _) {
            // not an instant, try the next form
        }
        try {
            return DateTimeFormatter.ISO_DATE.parse(text, LocalDate::from);
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    /**
     * Optional string argument at {@code index}; missing, null or blank yields {@code null}.
     */
    private static String optionalArgument(List<?> arguments, int index) {
        if (arguments.size() <= index) {
            return null;
        }
        Object value = unwrap(arguments.get(index));
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private static ZoneId zoneArgument(List<?> arguments, int index) {
        String zone = optionalArgument(arguments, index);
        return zone != null ? ZoneId.of(zone) : null;
    }

    /**
     * Optional BCP-47 locale argument; {@code de_DE} is accepted as {@code de-DE}.
     */
    private static Locale localeArgument(List<?> arguments, int index) {
        String tag = optionalArgument(arguments, index);
        return tag != null ? Locale.forLanguageTag(tag.replace('_', '-')) : null;
    }

    /**
     * A helper's text argument; null becomes the empty string rather than "null".
     */
    private static String textArgument(Object argument) {
        Object value = unwrap(argument);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Section markers recognised in email templates.
     */
    enum Marker {
        SUBJECT_OPEN("[SUBJECT]"),
        SUBJECT_CLOSE("[/SUBJECT]"),
        BODY_OPEN("[BODY]"),
        BODY_CLOSE("[/BODY]");

        private final String literal;

        Marker(String literal) {
            this.literal = literal;
        }

        String literal() {
            return literal;
        }
    }

    /**
     * Per-render stand-ins for the section markers.
     * <p>
     * The markers in the template source are replaced with tokens built from a fresh random
     * UUID before rendering, and the rendered output is split on those tokens. Template data
     * cannot contain a token it has never seen, so a {@code [BODY]} or {@code [/SUBJECT]}
     * in a customer's name is just text.
     */
    static final class SectionMarkers {
        private final Map<Marker, String> tokens = new EnumMap<>(Marker.class);

        private SectionMarkers(String nonce) {
            for (Marker marker : Marker.values()) {
                tokens.put(marker, "\u0000" + marker.name() + ":" + nonce + "\u0000");
            }
        }

        static SectionMarkers random() {
            return new SectionMarkers(UUID.randomUUID().toString());
        }

        String token(Marker marker) {
            return tokens.get(marker);
        }

        /** Replace every marker in the template source with its token. */
        String tokenize(String source) {
            String result = source;
            for (Marker marker : Marker.values()) {
                result = result.replace(marker.literal(), tokens.get(marker));
            }
            return result;
        }

        /** Turn tokens left in a section back into the literal markers. */
        String restore(String text) {
            if (text == null) {
                return null;
            }
            String result = text;
            for (Marker marker : Marker.values()) {
                result = result.replace(tokens.get(marker), marker.literal());
            }
            return result;
        }
    }

    /**
     * Unwrap FreeMarker template model to Java object.
     */
    private static Object unwrap(Object obj) {
        if (obj instanceof freemarker.template.TemplateModel tm) {
            try {
                return freemarker.template.utility.DeepUnwrap.unwrap(tm);
            } catch (Exception _) {
                return obj;
            }
        }
        return obj;
    }
}
