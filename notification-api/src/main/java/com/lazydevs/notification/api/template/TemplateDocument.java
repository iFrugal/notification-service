package com.lazydevs.notification.api.template;

import java.util.Objects;

/**
 * Template source text as a {@link TemplateSource} or a {@link TemplateStore} serves it to
 * the engine (DD-38).
 *
 * @param name         name relative to the base path, for example {@code default/email/WELCOME.ftl};
 *                     relative includes resolve against it and errors name it
 * @param content      the source text
 * @param lastModified modification time in epoch milliseconds, or {@code -1} when unknown;
 *                     FreeMarker re-reads a cached include when it changes
 * @param origin       where it came from
 * @param version      published store version, {@code 0} for a built-in template
 * @since 1.3.0
 */
public record TemplateDocument(String name, String content, long lastModified, TemplateOrigin origin, int version) {

    public TemplateDocument {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(origin, "origin");
    }

    /** A built-in template. */
    public static TemplateDocument builtIn(String name, String content, long lastModified) {
        return new TemplateDocument(name, content, lastModified, TemplateOrigin.BUILT_IN, 0);
    }
}
