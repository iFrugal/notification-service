package com.lazydevs.notification.core.template;

import com.lazydevs.notification.api.template.TemplateDocument;
import com.lazydevs.notification.api.template.TemplateKey;
import com.lazydevs.notification.api.template.TemplateSource;
import com.lazydevs.notification.core.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The default {@link TemplateSource}: the files under {@code notification.template.base-path},
 * read through Spring's {@link ResourceLoader} ({@code classpath:} and {@code file:} alike),
 * exactly as every release before 1.3.0 read them (DD-04, DD-26, DD-38).
 * <p>
 * A top-level template is {@code <base-path><tenant>/<channel>/<id>.ftl}, read as UTF-8 with
 * its lines joined by {@code \n}. When it is missing, the location 1.1.1 and earlier used by
 * mistake ({@code <base-path>templates/<path>}) is still tried and a WARN is logged once per
 * file. Includes are read with the charset the engine asks for.
 * <p>
 * Messages are logged under the {@link NotificationTemplateEngine} category, where they were
 * logged before this class existed, so existing log configuration keeps applying.
 */
public class ResourceLoaderTemplateSource implements TemplateSource {

    @SuppressWarnings("java:S3416") // deliberately the engine's category, where these messages were logged before
    private static final Logger log = LoggerFactory.getLogger(NotificationTemplateEngine.class);

    /**
     * Extra path segment that 1.1.1 and earlier inserted under the base path.
     * Still tried after the correct location.
     */
    private static final String LEGACY_SEGMENT = "templates/";

    /**
     * A relative template path: safe segments separated by {@code /}. Every path this source
     * is asked for was built from, or checked against, the template id rule already; this
     * guard keeps a path that could leave the base path away from the {@link ResourceLoader}.
     */
    private static final Pattern RELATIVE_PATH =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}+(/[A-Za-z0-9][A-Za-z0-9._-]{0,127}+)*+$");

    private static final String CLASSPATH = "classpath:";
    private static final String CLASSPATH_ALL = "classpath*:";

    private final ResourceLoader resourceLoader;
    private final Supplier<String> basePath;

    /** Legacy template locations already warned about, so each is logged once. */
    private final Set<String> warnedLegacyPaths = ConcurrentHashMap.newKeySet();

    /**
     * @param basePath the template root, for example {@code classpath:/templates/}; a missing
     *                 trailing slash is added
     */
    public ResourceLoaderTemplateSource(ResourceLoader resourceLoader, String basePath) {
        this(resourceLoader, () -> normalise(basePath));
    }

    /** Reads {@code notification.template.base-path} on every lookup. */
    public ResourceLoaderTemplateSource(ResourceLoader resourceLoader, NotificationProperties properties) {
        this(resourceLoader, () -> normalise(properties.getTemplate().getBasePath()));
    }

    private ResourceLoaderTemplateSource(ResourceLoader resourceLoader, Supplier<String> basePath) {
        this.resourceLoader = resourceLoader;
        this.basePath = basePath;
    }

    /** The base path with a trailing slash so relative paths append cleanly; {@code ""} when unset. */
    static String normalise(String basePath) {
        if (basePath == null) {
            return "";
        }
        return basePath.isEmpty() || basePath.endsWith("/") ? basePath : basePath + "/";
    }

    @Override
    public Optional<TemplateDocument> find(TemplateKey key) {
        if (!key.locale().isEmpty()) {
            return Optional.empty();
        }
        String path = key.name();
        TemplateDocument content = load(path);
        if (content == null) {
            String legacyPath = LEGACY_SEGMENT + path;
            content = load(legacyPath);
            if (content != null && warnedLegacyPaths.add(legacyPath) && log.isWarnEnabled()) {
                String base = basePath.get();
                log.warn("Template {}{} was loaded from its pre-1.1.2 location, which repeats 'templates/' "
                                + "under notification.template.base-path={}. Move it to {}{} or set the base path "
                                + "to {}{}; this fallback will be removed in 1.2.",
                        base, loggable(legacyPath), base, base, loggable(path), base, LEGACY_SEGMENT);
            }
        }
        return Optional.ofNullable(content);
    }

    private TemplateDocument load(String path) {
        try {
            Resource resource = resource(path);
            if (resource != null && resource.exists()) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    return TemplateDocument.builtIn(path, reader.lines().collect(Collectors.joining("\n")),
                            lastModified(resource));
                }
            }
        } catch (Exception e) {
            log.debug("Failed to load template from {}: {}", loggable(path), loggable(e.getMessage()));
        }
        return null;
    }

    /**
     * @throws UncheckedIOException when the template exists but cannot be read
     */
    @Override
    public Optional<TemplateDocument> findInclude(String name, Charset charset) {
        Resource resource = resource(name);
        if (resource == null || !resource.exists()) {
            return Optional.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            return Optional.of(TemplateDocument.builtIn(name, new String(in.readAllBytes(), charset),
                    lastModified(resource)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The resource at {@code path} under the base path, or {@code null} when the path is not a safe relative path. */
    private Resource resource(String path) {
        if (path == null || path.contains("..")) {
            return null;
        }
        // Relative, no backslash, and only safe segments: the path stays under the base path
        if (path.startsWith("/") || path.contains("\\") || !RELATIVE_PATH.matcher(path).matches()) {
            return null;
        }
        return resourceLoader.getResource(basePath.get() + path);
    }

    /** {@code text} with line breaks replaced, so it cannot forge log lines. */
    static String loggable(String text) {
        return text == null ? null : text.replace('\n', '_').replace('\r', '_');
    }

    /** Modification time, or {@code -1} when unknown (for example an entry in a nested jar). */
    static long lastModified(Resource resource) {
        try {
            return resource.lastModified();
        } catch (IOException _) {
            return -1;
        }
    }

    /**
     * Every {@code <tenant>/<channel>/<id>.ftl} directly under the base path whose parts are
     * valid key segments; {@code classpath:} base paths are searched in every classpath root.
     * Partials whose name is not a valid id (such as {@code _layout.ftl}) are left out.
     */
    @Override
    public List<TemplateKey> list() {
        String base = basePath.get();
        if (base.startsWith(CLASSPATH)) {
            base = CLASSPATH_ALL + base.substring(CLASSPATH.length());
        }
        ResourcePatternResolver resolver = resourceLoader instanceof ResourcePatternResolver patterns
                ? patterns
                : new PathMatchingResourcePatternResolver(resourceLoader);
        Set<TemplateKey> keys = new TreeSet<>(Comparator.comparing(TemplateKey::name));
        try {
            for (Resource resource : resolver.getResources(base + "*/*/*" + TemplateKey.EXTENSION)) {
                keyOf(resource).ifPresent(keys::add);
            }
        } catch (IOException e) {
            log.debug("Cannot list templates under {}: {}", base, e.getMessage());
        }
        return new ArrayList<>(keys);
    }

    private static Optional<TemplateKey> keyOf(Resource resource) {
        try {
            String[] segments = resource.getURL().toString().split("/");
            if (segments.length < 3) {
                return Optional.empty();
            }
            int n = segments.length;
            return TemplateKey.fromName(segments[n - 3] + "/" + segments[n - 2] + "/" + segments[n - 1]);
        } catch (IOException _) {
            return Optional.empty();
        }
    }
}
