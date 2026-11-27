package com.lazydevs.notification.api.template;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Optional;

/**
 * Where the engine reads built-in templates (DD-38). Both lookup paths of the engine go
 * through it: top-level templates by {@link TemplateKey} and {@code <#include>} or
 * {@code <#import>} targets by name.
 *
 * <p>The default implementation reads {@code notification.template.base-path} through
 * Spring's {@code ResourceLoader}, as every release before 1.3.0 did. Declare a bean of this
 * type to read built-in templates from somewhere else. A {@link TemplateStore}, when one is
 * configured, is consulted before the source at each tenant level.
 *
 * <p>Lookups are exact: the engine applies the tenant-then-default fallback itself.
 * Implementations must be thread-safe.
 *
 * @since 1.3.0
 */
public interface TemplateSource {

    /**
     * The template for exactly this key.
     *
     * @return the template, empty when this source has none for the key
     */
    Optional<TemplateDocument> find(TemplateKey key);

    /**
     * An included or imported template by its name relative to the base path. The engine
     * has already resolved the name against the including template and checked that every
     * segment is a safe path segment.
     *
     * @param charset charset to decode stored bytes with: UTF-8, or the encoding an
     *                {@code <#ftl encoding="...">} header in the template asks for; sources
     *                that hold text ignore it
     * @return the template, empty when this source has none by that name
     */
    Optional<TemplateDocument> findInclude(String name, Charset charset);

    /**
     * Keys of the top-level templates this source holds, for the admin listing. Sources that
     * cannot enumerate their templates return an empty list, which is the default.
     */
    default List<TemplateKey> list() {
        return List.of();
    }
}
