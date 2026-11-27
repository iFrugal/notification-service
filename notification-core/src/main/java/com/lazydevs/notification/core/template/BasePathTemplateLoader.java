package com.lazydevs.notification.core.template;

import com.lazydevs.notification.api.template.TemplateDocument;
import com.lazydevs.notification.api.template.TemplateSource;
import freemarker.cache.TemplateLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * FreeMarker {@link TemplateLoader} for {@code <#include>} and {@code <#import>}, so they
 * resolve against the same tree as the top-level templates (DD-26), through the engine's
 * {@link TemplateSource}s in order: the template store's published versions first when one is
 * configured, then the built-in source (DD-38).
 * <p>
 * FreeMarker has already resolved relative names against the including template and
 * refused names that back out of the root when it calls this loader. Each segment of the
 * name must still be a safe path segment (the 1.1.2 id rule), so a name cannot leave the
 * base path or name another scheme; anything else is reported as not found.
 */
@Slf4j
final class BasePathTemplateLoader implements TemplateLoader {

    /** Charset templates are first decoded with: the engine's default encoding. */
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    private final List<TemplateSource> sources;
    private final Predicate<String> safeSegment;

    /**
     * @param sources     consulted in order; the first that has the name wins
     * @param safeSegment the rule every path segment of a template name must pass
     */
    BasePathTemplateLoader(List<TemplateSource> sources, Predicate<String> safeSegment) {
        this.sources = List.copyOf(sources);
        this.safeSegment = safeSegment;
    }

    /**
     * Over the files under {@code basePath} only.
     *
     * @param basePath base path ending in {@code /} (or empty)
     */
    BasePathTemplateLoader(ResourceLoader resourceLoader, String basePath, Predicate<String> safeSegment) {
        this(List.of(new ResourceLoaderTemplateSource(resourceLoader, basePath)), safeSegment);
    }

    @Override
    public Object findTemplateSource(String name) throws IOException {
        if (!isSafeName(name)) {
            log.warn("Rejected template include '{}': every path segment must be a valid template path segment",
                    NotificationTemplateEngine.mask(name));
            return null;
        }
        try {
            for (TemplateSource source : sources) {
                Optional<TemplateDocument> document = source.findInclude(name, DEFAULT_CHARSET);
                if (document.isPresent()) {
                    return new Found(source, document.get());
                }
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return null;
    }

    private boolean isSafeName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (String segment : name.split("/", -1)) {
            if (!safeSegment.test(segment)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public long getLastModified(Object templateSource) {
        return ((Found) templateSource).document().lastModified();
    }

    /**
     * The text already read, or, when an {@code <#ftl encoding>} header asks for another
     * charset than the default, the template read again from its source with that charset.
     */
    @Override
    public Reader getReader(Object templateSource, String encoding) throws IOException {
        Found found = (Found) templateSource;
        Charset charset = Charset.forName(encoding);
        if (charset.equals(DEFAULT_CHARSET)) {
            return new StringReader(found.document().content());
        }
        try {
            TemplateDocument reread = found.source().findInclude(found.document().name(), charset)
                    .orElseThrow(() -> new IOException("Template " + found.document().name() + " disappeared"));
            return new StringReader(reread.content());
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @Override
    public void closeTemplateSource(Object templateSource) {
        // Nothing to close: the text was read when the template was found
    }

    /**
     * A template found in one source. FreeMarker compares these to decide whether its cached
     * copy is still current, so equality covers the source, the name, the time and the text.
     */
    record Found(TemplateSource source, TemplateDocument document) {
    }
}
