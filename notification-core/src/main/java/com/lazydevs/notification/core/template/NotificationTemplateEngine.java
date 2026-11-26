package com.lazydevs.notification.core.template;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.TemplateNotFoundException;
import com.lazydevs.notification.api.exception.TemplateRenderException;
import com.lazydevs.notification.api.exception.TemplateRenderException.Reason;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.core.config.NotificationProperties;
import freemarker.cache.MruCacheStorage;
import freemarker.cache.NullCacheStorage;
import freemarker.cache.TemplateNameFormat;
import freemarker.core.Environment;
import freemarker.core.HTMLOutputFormat;
import freemarker.core.ParseException;
import freemarker.core.TemplateClassResolver;
import freemarker.core.TemplateHTMLOutputModel;
import freemarker.template.Configuration;
import freemarker.template.MalformedTemplateNameException;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;
import lazydevs.persistence.connection.multitenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringEscapeUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
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
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Notification-aware FreeMarker template engine.
 * - Tenant-aware template resolution
 * - Template caching (bounded, with a TTL)
 * - Notification-specific helper methods
 * - Optional HTML auto-escaping of email bodies
 * - Its own FreeMarker {@link Configuration} (DD-26): includes resolve against the base
 *   path, render errors are thrown once as {@link TemplateRenderException} and never logged
 *   by FreeMarker, and the persistence-utils legacy helpers are opt-out
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

    /**
     * Allowed tenant, channel and template ids. They become path segments under the base
     * path, so anything that could leave it (separators, "..") is rejected up front.
     */
    private static final Pattern SAFE_PATH_SEGMENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    /** A run of whitespace or line separators; possessive, so matching never backtracks. */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[\\s\\u0085\\u2028\\u2029]++");

    /** Characters of a rejected id that are logged; the rest is masked. */
    private static final int LOGGED_ID_PREFIX = 8;

    /** Lower-case fragments whose presence marks an email body as HTML. */
    private static final List<String> HTML_MARKERS = List.of(
            "<html", "<body", "<div", "<p>", "<table", "<br", "<span", "<a ", "<!doctype");

    private static final String OUTPUT_FORMAT_HTML_OPEN = "<#outputformat \"HTML\">";
    private static final String OUTPUT_FORMAT_CLOSE = "</#outputformat>";

    /**
     * Separator between a failure's description and FreeMarker's tips in its messages.
     */
    private static final String FREEMARKER_TIPS = "\n\n----";

    private final NotificationProperties properties;
    private final ResourceLoader resourceLoader;

    /** This engine's own FreeMarker configuration; never shared with other code. */
    private final Configuration configuration;

    /**
     * Template cache: (tenantId, channel/templateKey) -> template name and content.
     * Bounded by {@code cache-max-size} and expired {@code cache-ttl-seconds} after loading.
     * Included templates are cached by FreeMarker itself with the same limits.
     */
    private final Cache<TemplateKey, LoadedTemplate> templateCache;

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
        this.configuration = buildConfiguration();
        if (properties.getTemplate().isLegacyHelpers()) {
            log.warn("notification.template.legacy-helpers=true: every template model gets the deprecated {} helpers, "
                            + "and ?new may instantiate any TemplateModel class; 'file' reads any path this process "
                            + "can read and 'js' runs script functions. Set notification.template.legacy-helpers=false "
                            + "unless your templates use them; the default becomes false in 2.0.",
                    PersistenceUtilsHelpers.LEGACY_HELPER_NAMES);
        }
    }

    /**
     * The FreeMarker settings persistence-utils used up to 1.2 (incompatible improvements
     * 2.3.23, UTF-8, {@code Locale.US}, {@code computer} number format), so templates render
     * the same, plus: includes load from the base path, errors are rethrown without FreeMarker
     * logging them, and unchecked helper exceptions become template errors with a location.
     */
    private Configuration buildConfiguration() {
        NotificationProperties.TemplateProperties template = properties.getTemplate();
        Configuration config = new Configuration(Configuration.VERSION_2_3_23);
        config.setDefaultEncoding(StandardCharsets.UTF_8.name());
        config.setLocale(Locale.US);
        config.setNumberFormat("computer");
        config.setTemplateExceptionHandler(new VariablePathRecorder());
        config.setLogTemplateExceptions(false);
        config.setWrapUncheckedExceptions(true);
        config.setTemplateLoader(
                new BasePathTemplateLoader(resourceLoader, basePath(), NotificationTemplateEngine::isSafeSegment));
        config.setTemplateNameFormat(TemplateNameFormat.DEFAULT_2_4_0);
        config.setLocalizedLookup(false);
        config.setNewBuiltinClassResolver(template.isLegacyHelpers()
                ? TemplateClassResolver.UNRESTRICTED_RESOLVER
                : TemplateClassResolver.SAFER_RESOLVER);
        configureIncludeCache(config, template);
        return config;
    }

    /**
     * FreeMarker caches included templates; apply the same switch, TTL and size as the
     * top-level template cache.
     */
    private static void configureIncludeCache(Configuration config, NotificationProperties.TemplateProperties template) {
        if (!template.isCacheEnabled()) {
            config.setCacheStorage(new NullCacheStorage());
            return;
        }
        config.setTemplateUpdateDelayMilliseconds(template.getCacheTtlSeconds() > 0
                ? Duration.ofSeconds(template.getCacheTtlSeconds()).toMillis()
                : Long.MAX_VALUE);
        if (template.getCacheMaxSize() > 0) {
            config.setCacheStorage(new MruCacheStorage((int) Math.min(template.getCacheMaxSize(), Integer.MAX_VALUE), 0));
        }
    }

    private static Cache<TemplateKey, LoadedTemplate> buildCache(NotificationProperties.TemplateProperties template,
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
        String tenantId = tenantOf(request);

        Channel channel = request.getChannel();
        String templateId = request.getTemplateId() != null
                ? request.getTemplateId()
                : request.getNotificationType();

        // Resolve and load template
        LoadedTemplate template = resolveTemplate(tenantId, channel, templateId);
        String templateContent = template.source();

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

        String renderedContent = process(new RenderTarget(tenantId, templateId, template.name()), source, enrichedData);

        // Parse the rendered content based on channel
        return parseRenderedContent(channel, renderedContent, templateId, markers, htmlBody);
    }

    /**
     * Render the request's template as plain text with this engine's template lookup,
     * helpers and FreeMarker configuration, after {@code prepareSource} rewrote the
     * template source; for a {@link ChannelContentRenderer} that parses its own sections.
     *
     * <p>The template is resolved exactly as {@link #render} resolves it (tenant, then
     * default; cached), so any template source the engine is configured with serves both.
     * {@code prepareSource} sees the source as stored, for example to swap section markers
     * for per-render random tokens that template data cannot forge; it must not load
     * anything itself.
     *
     * @param request       the request; its channel, template id or notification type and
     *                      tenant select the template
     * @param prepareSource rewrites the template source before rendering, not {@code null}
     * @return the tenant and template id rendered, and the output
     * @throws com.lazydevs.notification.api.exception.TemplateNotFoundException when there is no template
     * @throws TemplateRenderException when the template is invalid or does not fit the data
     * @since 1.4.0
     */
    public RawOutput renderRaw(NotificationRequest request, UnaryOperator<String> prepareSource) {
        Objects.requireNonNull(prepareSource, "prepareSource");
        String tenantId = tenantOf(request);
        String templateId = request.getTemplateId() != null
                ? request.getTemplateId()
                : request.getNotificationType();
        LoadedTemplate template = resolveTemplate(tenantId, request.getChannel(), templateId);
        String source = prepareSource.apply(template.source());
        String output = process(new RenderTarget(tenantId, templateId, template.name()), source,
                enrichWithHelpers(request.getTemplateData()));
        return new RawOutput(tenantId, templateId, output);
    }

    /**
     * The output of {@link #renderRaw}.
     *
     * @param tenantId   the tenant whose template was rendered (its own or the default one)
     * @param templateId the template id rendered
     * @param output     the rendered text, untrimmed
     * @since 1.4.0
     */
    public record RawOutput(String tenantId, String templateId, String output) {
    }

    /**
     * {@link #render} and refuse output that cannot be sent: an email with a blank subject or
     * with neither a text nor an HTML body ({@link Reason#EMPTY_OUTPUT}). Used by the send
     * path when {@code notification.template.validate-before-dispatch} is on (DD-27).
     *
     * @throws TemplateRenderException when the template is missing, invalid, does not fit
     *                                 the data or renders empty
     */
    public RenderedContent renderAndValidate(NotificationRequest request) {
        RenderedContent content = render(request);
        if (request.getChannel() == Channel.EMAIL) {
            String problem = emailProblem(content);
            if (problem != null) {
                throw new TemplateRenderException(Reason.EMPTY_OUTPUT, tenantOf(request), content.templateId(), null,
                        "Template " + content.templateId() + " rendered an email whose " + problem, null);
            }
        }
        return content;
    }

    /** Why a rendered email cannot be sent, or {@code null} when it can. */
    private static String emailProblem(RenderedContent content) {
        if (isBlank(content.subject())) {
            return "subject is blank";
        }
        return isBlank(content.textBody()) && isBlank(content.htmlBody()) ? "body is empty" : null;
    }

    /**
     * The tenant whose templates a request renders: its own, the context's, or the default.
     */
    private String tenantOf(NotificationRequest request) {
        String tenantId = request.getTenantId() != null ? request.getTenantId() : TenantContext.getTenantId();
        return tenantId != null ? tenantId : properties.getDefaultTenant();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Render {@code source} with this engine's FreeMarker configuration.
     * Package-private and overridable so a test can compare the output with the 1.2 path.
     *
     * @throws TemplateRenderException with {@link Reason#INVALID_TEMPLATE} for a syntax error
     *                                 or a missing include, {@link Reason#MODEL_INVALID} otherwise
     */
    String process(RenderTarget target, String source, Map<String, Object> model) {
        Environment env = null;
        try {
            Template template = new Template(target.name(), source, configuration);
            StringWriter out = new StringWriter();
            env = template.createProcessingEnvironment(model, out);
            VariablePathRecorder.bind(env, model);
            env.process();
            return out.toString();
        } catch (TemplateException e) {
            boolean templateProblem = isTemplateProblem(e);
            String path = templateProblem || env == null ? null : VariablePathRecorder.variablePath(env, e);
            throw renderFailure(target, templateProblem ? Reason.INVALID_TEMPLATE : Reason.MODEL_INVALID,
                    path, describe(e), e);
        } catch (IOException e) {
            // A ParseException: the source is not valid FreeMarker (the writer cannot fail)
            throw renderFailure(target, Reason.INVALID_TEMPLATE, null, oneLine(e.getMessage()), e);
        }
    }

    /**
     * Whether a processing error is the template's fault rather than the data's: an
     * {@code <#include>} or {@code <#import>} that is missing, badly named or not valid FreeMarker.
     */
    private static boolean isTemplateProblem(TemplateException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof freemarker.template.TemplateNotFoundException
                    || cause instanceof MalformedTemplateNameException
                    || cause instanceof ParseException) {
                return true;
            }
        }
        return false;
    }

    /**
     * FreeMarker's description of the failure, without its tips and FTL stack trace, on one
     * line, with the location appended when the description does not name it.
     */
    private static String describe(TemplateException e) {
        String description = e.getMessageWithoutStackTop();
        int tips = description.indexOf(FREEMARKER_TIPS);
        if (tips >= 0) {
            description = description.substring(0, tips);
        }
        description = oneLine(description);
        if (!description.contains("[in template") && e.getLineNumber() != null) {
            description += " [in template \"" + e.getTemplateSourceName() + "\" at line " + e.getLineNumber()
                    + ", column " + e.getColumnNumber() + "]";
        }
        return description;
    }

    /**
     * {@code text} stripped, with every run of whitespace that contains a line break
     * collapsed into one space; linear in the length of the text.
     */
    static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        return WHITESPACE_RUN.matcher(text.strip())
                .replaceAll(run -> containsLineBreak(run.group()) ? " " : Matcher.quoteReplacement(run.group()));
    }

    private static boolean containsLineBreak(String whitespace) {
        return whitespace.chars().anyMatch(c -> c == '\n' || c == '\r' || c == '\u000B' || c == '\f'
                || c == '\u0085' || c == '\u2028' || c == '\u2029');
    }

    private static TemplateRenderException renderFailure(RenderTarget target, Reason reason, String path,
                                                         String description, Exception cause) {
        return new TemplateRenderException(reason, target.tenantId(), target.templateId(), path,
                "Template " + target.templateId() + " for tenant " + target.tenantId() + " could not be rendered: "
                        + description, cause);
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
    private LoadedTemplate resolveTemplate(String tenantId, Channel channel, String templateId) {
        // Validate before any path or cache key is built from the ids
        requireSafeSegment("tenantId", tenantId);
        requireSafeSegment("channel", channel.name().toLowerCase());
        requireSafeSegment("templateId", templateId);

        String cacheKey = channel.name().toLowerCase() + "/" + templateId;

        // Check cache; a TemplateNotFoundException from the loader is not cached
        if (properties.getTemplate().isCacheEnabled()) {
            return templateCache.get(new TemplateKey(tenantId, cacheKey),
                    key -> loadTemplateOrThrow(tenantId, channel, templateId));
        }
        return loadTemplateOrThrow(tenantId, channel, templateId);
    }

    private LoadedTemplate loadTemplateOrThrow(String tenantId, Channel channel, String templateId) {
        // Try tenant-specific template
        LoadedTemplate content = loadTemplateWithLegacyFallback(tenantId, channel.name().toLowerCase(), templateId);

        // Fallback to default
        if (content == null) {
            content = loadTemplateWithLegacyFallback(DEFAULT_TENANT_DIR, channel.name().toLowerCase(), templateId);
        }

        if (content == null) {
            throw new TemplateNotFoundException(tenantId, channel.name().toLowerCase(), templateId);
        }

        return content;
    }

    /**
     * Load {@code <tenantDir>/<channelDir>/<templateId>.ftl} relative to the base path. If it
     * is missing, try the location 1.1.1 and earlier used by mistake
     * ({@code <basePath>templates/<path>}) so custom layouts that relied on it keep working
     * for one release, and warn once per file. Both paths are built from validated segments.
     */
    private LoadedTemplate loadTemplateWithLegacyFallback(String tenantDir, String channelDir, String templateId) {
        requireSafeSegment("tenantId", tenantDir);
        requireSafeSegment("channel", channelDir);
        requireSafeSegment("templateId", templateId);
        String path = tenantDir + "/" + channelDir + "/" + templateId + ".ftl";

        LoadedTemplate content = loadTemplate(path);
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
     * Reject an id that is not a safe single path segment.
     * The exception names the field but not the value, which may be attacker-controlled;
     * the log shows only a masked prefix of it.
     */
    private static void requireSafeSegment(String field, String value) {
        if (isSafeSegment(value)) {
            return;
        }
        log.warn("Rejected template lookup: {} '{}' is not a valid template path segment", field, mask(value));
        throw new TemplateNotFoundException("Template not found: invalid " + field
                + "; it must be 1-128 letters, digits, '.', '_' or '-', start with a letter or digit, "
                + "and not contain two consecutive dots");
    }

    /**
     * Whether {@code value} is a safe single path segment: 1-128 letters, digits, '.', '_' or
     * '-', starting with a letter or digit, without "..". Applies to ids and to every segment
     * of an included template's name.
     */
    static boolean isSafeSegment(String value) {
        return value != null
                && SAFE_PATH_SEGMENT.matcher(value).matches()
                && !value.contains("..")
                && !value.contains("/")
                && !value.contains("\\");
    }

    /**
     * First {@value #LOGGED_ID_PREFIX} characters of {@code value}, non-printable ones replaced,
     * followed by "..." when anything was cut.
     */
    static String mask(String value) {
        if (value == null) {
            return "null";
        }
        String prefix = value.length() > LOGGED_ID_PREFIX ? value.substring(0, LOGGED_ID_PREFIX) : value;
        StringBuilder masked = new StringBuilder(prefix.length() + 3);
        prefix.chars().forEach(c -> masked.append(c < 0x20 || c == 0x7f ? '?' : (char) c));
        return value.length() > LOGGED_ID_PREFIX ? masked.append("...").toString() : masked.toString();
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

    private LoadedTemplate loadTemplate(String path) {
        try {
            String fullPath = basePath() + path;
            Resource resource = resourceLoader.getResource(fullPath);
            if (resource.exists()) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    return new LoadedTemplate(path, reader.lines().collect(Collectors.joining("\n")));
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

        // uuid and trim, plus eval, file, js and serde_* with legacy-helpers=true (DD-26)
        PersistenceUtilsHelpers.addTo(enriched, properties.getTemplate().isLegacyHelpers());

        return enriched;
    }

    /**
     * Clear template cache for a tenant.
     * Included templates are shared between tenants, so FreeMarker's cache of them is
     * cleared completely.
     */
    public void clearCache(String tenantId) {
        templateCache.asMap().keySet().removeIf(key -> Objects.equals(key.tenantId(), tenantId));
        configuration.clearTemplateCache();
        log.debug("Cleared template cache for tenant: {}", tenantId);
    }

    /**
     * Clear all template caches.
     */
    public void clearAllCache() {
        templateCache.invalidateAll();
        configuration.clearTemplateCache();
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
     * A top-level template: its name relative to the base path, which relative includes
     * resolve against, and its source.
     */
    record LoadedTemplate(String name, String source) {
    }

    /**
     * What is being rendered, for error reporting: tenant, template id and the template name.
     */
    record RenderTarget(String tenantId, String templateId, String name) {
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
