package com.lazydevs.notification.core.template;

import freemarker.cache.TemplateLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.function.Predicate;

/**
 * FreeMarker {@link TemplateLoader} over {@code notification.template.base-path}, so
 * {@code <#include>} and {@code <#import>} resolve against the same tree as the top-level
 * templates (DD-26), through Spring's {@link ResourceLoader} ({@code classpath:} and
 * {@code file:} alike).
 * <p>
 * FreeMarker has already resolved relative names against the including template and
 * refused names that back out of the root when it calls this loader. Each segment of the
 * name must still be a safe path segment (the 1.1.2 id rule), so a name cannot leave the
 * base path or name another scheme; anything else is reported as not found.
 */
@Slf4j
final class BasePathTemplateLoader implements TemplateLoader {

    private final ResourceLoader resourceLoader;
    private final String basePath;
    private final Predicate<String> safeSegment;

    /**
     * @param basePath    base path ending in {@code /} (or empty)
     * @param safeSegment the rule every path segment of a template name must pass
     */
    BasePathTemplateLoader(ResourceLoader resourceLoader, String basePath, Predicate<String> safeSegment) {
        this.resourceLoader = resourceLoader;
        this.basePath = basePath;
        this.safeSegment = safeSegment;
    }

    @Override
    public Object findTemplateSource(String name) {
        if (!isSafeName(name)) {
            log.warn("Rejected template include '{}': every path segment must be a valid template path segment",
                    NotificationTemplateEngine.mask(name));
            return null;
        }
        Resource resource = resourceLoader.getResource(basePath + name);
        return resource.exists() ? resource : null;
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
        try {
            return ((Resource) templateSource).lastModified();
        } catch (IOException _) {
            // Unknown (for example an entry in a nested jar): FreeMarker then keeps the cached copy
            return -1;
        }
    }

    @Override
    public Reader getReader(Object templateSource, String encoding) throws IOException {
        return new InputStreamReader(((Resource) templateSource).getInputStream(), Charset.forName(encoding));
    }

    @Override
    public void closeTemplateSource(Object templateSource) {
        // Nothing to close: getReader opens a fresh stream that FreeMarker closes itself
    }
}
