package com.lazydevs.notification.core.template;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.TemplateNotFoundException;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.core.config.NotificationProperties;
import freemarker.core.HTMLOutputFormat;
import freemarker.core.TemplateHTMLOutputModel;
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
import java.time.Duration;
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
 * - Template caching (bounded, with a TTL)
 * - Notification-specific helper methods
 * - Optional HTML auto-escaping of email bodies
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

    /** Lower-case fragments whose presence marks an email body as HTML. */
    private static final List<String> HTML_MARKERS = List.of(
            "<html", "<body", "<div", "<p>", "<table", "<br", "<span", "<a ", "<!doctype");

    private static final String OUTPUT_FORMAT_HTML_OPEN = "<#outputformat \"HTML\">";
    private static final String OUTPUT_FORMAT_CLOSE = "</#outputformat>";

    private final TemplateEngine coreEngine = TemplateEngine.getInstance();
    private final NotificationProperties properties;
    private final ResourceLoader resourceLoader;

    /**
     * Template cache: (tenantId, channel/templateKey) -> template content.
     * Bounded by {@code cache-max-size} and expired {@code cache-ttl-seconds} after loading.
     */
    private final Cache<TemplateKey, String> templateCache;

    /** Legacy template locations already warned about, so each is logged once. */
    private final Set<String> warnedLegacyPaths = ConcurrentHashMap.newKeySet();

    public NotificationTemplateEngine(NotificationProperties properties, ResourceLoader resourceLoader) {
        this(properties, resourceLoader, Ticker.systemTicker());
    }

    /**
     * Visible for tests, which drive cache expiry with a fake {@link Ticker}.
     */
    NotificationTemplateEngine(NotificationProperties properties, ResourceLoader resourceLoader, Ticker ticker) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.templateCache = buildCache(properties.getTemplate(), ticker);
    }

    private static Cache<TemplateKey, String> buildCache(NotificationProperties.TemplateProperties template,
                                                         Ticker ticker) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder().ticker(ticker);
        if (template.getCacheTtlSeconds() > 0) {
            builder.expireAfterWrite(Duration.ofSeconds(template.getCacheTtlSeconds()));
        }
        if (template.getCacheMaxSize() > 0) {
            builder.maximumSize(template.getCacheMaxSize());
        }
        return builder.build();
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

        // With auto-escape on, the body's template source decides whether it is HTML,
        // and an HTML body is rendered in FreeMarker's HTML output format. A [TEXT]
        // section makes the body the HTML part, so it is escaped whatever it contains.
        Boolean htmlBody = null;
        if (markers != null && properties.getTemplate().isAutoEscape()) {
            String tokenized = source;
            List<int[]> bodyRanges = bodySourceRanges(tokenized, markers);
            boolean html = textSectionRange(tokenized, markers) != null
                    || looksLikeHtml(bodyRanges.stream()
                            .map(range -> tokenized.substring(range[0], range[1]))
                            .collect(Collectors.joining()));
            if (html) {
                source = wrapInHtmlOutputFormat(source, bodyRanges);
            }
            htmlBody = html;
        }

        // Generate content using core engine
        String renderedContent = coreEngine.generate(source, enrichedData);

        // Parse the rendered content based on channel
        return parseRenderedContent(channel, renderedContent, templateId, markers, htmlBody);
    }

    /**
     * The parts of the (tokenized) template source that render into the email body, found
     * with the same rules {@link #parseEmailContent} applies to the output, minus any
     * {@code [TEXT]} section.
     */
    private static List<int[]> bodySourceRanges(String source, SectionMarkers markers) {
        String subjectClose = markers.token(Marker.SUBJECT_CLOSE);
        String bodyOpen = markers.token(Marker.BODY_OPEN);
        int[] text = textSectionRange(source, markers);

        int bodyStart = source.indexOf(bodyOpen);
        int bodyEnd = source.indexOf(markers.token(Marker.BODY_CLOSE));
        int subjectEnd = source.indexOf(subjectClose);
        int start;
        int end;
        if (bodyStart >= 0 && bodyEnd > bodyStart) {
            start = bodyStart + bodyOpen.length();
            end = bodyEnd;
        } else if (subjectEnd > 0) {
            start = subjectEnd + subjectClose.length();
            end = source.length();
        } else {
            start = ftlHeaderEnd(source);
            end = source.length();
        }

        List<int[]> ranges = new ArrayList<>();
        if (text != null && text[0] < end && text[1] > start) {
            // The [TEXT] section, markers included, is never escaped
            int textStart = text[0] - markers.token(Marker.TEXT_OPEN).length();
            int textEnd = text[1] + markers.token(Marker.TEXT_CLOSE).length();
            if (textStart > start) {
                ranges.add(new int[]{start, textStart});
            }
            if (end > textEnd) {
                ranges.add(new int[]{textEnd, end});
            }
        } else {
            ranges.add(new int[]{start, end});
        }
        return ranges;
    }

    private static int[] textSectionRange(String content, SectionMarkers markers) {
        return sectionRange(content, markers.token(Marker.TEXT_OPEN), markers.token(Marker.TEXT_CLOSE));
    }

    /**
     * Content range between the first {@code open} and the first {@code close} after it, or null.
     */
    private static int[] sectionRange(String content, String open, String close) {
        int start = content.indexOf(open);
        int end = content.indexOf(close);
        return start >= 0 && end > start ? new int[]{start + open.length(), end} : null;
    }

    /**
     * Index just past a leading {@code <#ftl ...>} header, which must stay first, or 0.
     */
    private static int ftlHeaderEnd(String source) {
        String stripped = source.stripLeading();
        if (stripped.startsWith("<#ftl")) {
            int headerEnd = source.indexOf('>', source.length() - stripped.length());
            return headerEnd >= 0 ? headerEnd + 1 : 0;
        }
        return 0;
    }

    private static String wrapInHtmlOutputFormat(String source, List<int[]> ranges) {
        StringBuilder wrapped = new StringBuilder(source);
        // Last range first, so earlier offsets stay valid
        for (int i = ranges.size() - 1; i >= 0; i--) {
            int[] range = ranges.get(i);
            wrapped.insert(range[1], OUTPUT_FORMAT_CLOSE);
            wrapped.insert(range[0], OUTPUT_FORMAT_HTML_OPEN);
        }
        return wrapped.toString();
    }

    static boolean looksLikeHtml(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        return HTML_MARKERS.stream().anyMatch(lower::contains);
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

        // Check cache; a TemplateNotFoundException from the loader is not cached
        if (properties.getTemplate().isCacheEnabled()) {
            return templateCache.get(new TemplateKey(tenantId, cacheKey),
                    key -> loadTemplateOrThrow(tenantId, channel, templateId));
        }
        return loadTemplateOrThrow(tenantId, channel, templateId);
    }

    private String loadTemplateOrThrow(String tenantId, Channel channel, String templateId) {
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
                                                 SectionMarkers markers, Boolean htmlBody) {
        if (channel == Channel.EMAIL) {
            return parseEmailContent(content, templateId, markers, htmlBody);
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
     * [TEXT]
     * Optional plain-text alternative; when present the body is sent as the HTML part
     * [/TEXT]
     *
     * Or just plain content (treated as body)
     *
     * The markers were swapped for the per-render tokens in {@code markers} before
     * rendering, so only markers written in the template source delimit sections.
     *
     * @param htmlBody whether the body is HTML, as decided from the template source when
     *                 auto-escape is on; {@code null} to detect it from the rendered body
     */
    private RenderedContent parseEmailContent(String content, String templateId, SectionMarkers markers,
                                              Boolean htmlBody) {
        // Take out the optional plain-text part first
        String text = null;
        String textOpen = markers.token(Marker.TEXT_OPEN);
        String textClose = markers.token(Marker.TEXT_CLOSE);
        int[] textRange = sectionRange(content, textOpen, textClose);
        if (textRange != null) {
            text = markers.restore(content.substring(textRange[0], textRange[1]).trim());
            content = content.substring(0, textRange[0] - textOpen.length())
                    + content.substring(textRange[1] + textClose.length());
        }

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

        if (text != null) {
            // An explicit text part makes the body the HTML part of a multipart message
            return new RenderedContent(subject, text, body, templateId);
        }

        // Determine if HTML
        boolean isHtml = htmlBody != null ? htmlBody : looksLikeHtml(body);

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
        enriched.put("escapeHtml", new EscapeHtmlMethod(properties.getTemplate().isAutoEscape()));

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
        templateCache.asMap().keySet().removeIf(key -> Objects.equals(key.tenantId(), tenantId));
        log.debug("Cleared template cache for tenant: {}", tenantId);
    }

    /**
     * Clear all template caches.
     */
    public void clearAllCache() {
        templateCache.invalidateAll();
        log.debug("Cleared all template caches");
    }

    /**
     * Number of cached templates after pending evictions; visible for tests.
     */
    long cachedTemplateCount() {
        templateCache.cleanUp();
        return templateCache.estimatedSize();
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
     * <p>
     * With auto-escape on, the result is HTML markup output, so the body's HTML output
     * format prints it as is instead of escaping it a second time.
     */
    private static class EscapeHtmlMethod implements TemplateMethodModelEx {
        private final boolean markupOutput;

        EscapeHtmlMethod(boolean markupOutput) {
            this.markupOutput = markupOutput;
        }

        @Override
        public Object exec(List arguments) throws TemplateModelException {
            if (arguments.isEmpty()) return "";
            if (markupOutput && arguments.get(0) instanceof TemplateHTMLOutputModel alreadyEscaped) {
                return alreadyEscaped;
            }
            String text = textArgument(arguments.get(0));
            String escaped = StringEscapeUtils.escapeHtml4(text);
            return markupOutput ? HTMLOutputFormat.INSTANCE.fromMarkup(escaped) : escaped;
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
        if (argument instanceof TemplateHTMLOutputModel markup) {
            try {
                return HTMLOutputFormat.INSTANCE.getMarkupString(markup);
            } catch (TemplateModelException _) {
                return "";
            }
        }
        Object value = unwrap(argument);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Template cache key.
     */
    record TemplateKey(String tenantId, String template) {
    }

    /**
     * Section markers recognised in email templates.
     */
    enum Marker {
        SUBJECT_OPEN("[SUBJECT]"),
        SUBJECT_CLOSE("[/SUBJECT]"),
        BODY_OPEN("[BODY]"),
        BODY_CLOSE("[/BODY]"),
        TEXT_OPEN("[TEXT]"),
        TEXT_CLOSE("[/TEXT]");

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
