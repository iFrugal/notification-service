package com.lazydevs.notification.core.template;

import com.lazydevs.notification.api.template.TemplateDocument;
import com.lazydevs.notification.api.template.TemplateKey;
import com.lazydevs.notification.api.template.TemplateOrigin;
import com.lazydevs.notification.api.template.TemplateSource;
import com.lazydevs.notification.api.template.TemplateStore;
import com.lazydevs.notification.api.template.TemplateVersion;

import java.nio.charset.Charset;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Serves the published FreeMarker versions of a {@link TemplateStore} to the engine, for
 * top-level templates and for includes named {@code <tenant>/<channel>/<id>.ftl} (DD-38).
 * Drafts and JSON content are never served.
 */
final class StoreTemplateSource implements TemplateSource {

    private final TemplateStore store;

    StoreTemplateSource(TemplateStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public Optional<TemplateDocument> find(TemplateKey key) {
        return store.findPublished(key)
                .filter(version -> TemplateVersion.FREEMARKER.equals(version.contentType()))
                .map(version -> new TemplateDocument(key.name(), normaliseLineBreaks(version.content()),
                        version.publishedAt() == null ? -1 : version.publishedAt().toEpochMilli(),
                        TemplateOrigin.STORE, version.version()));
    }

    @Override
    public Optional<TemplateDocument> findInclude(String name, Charset charset) {
        return TemplateKey.fromName(name)
                .flatMap(store::findPublished)
                .filter(version -> TemplateVersion.FREEMARKER.equals(version.contentType()))
                .map(version -> new TemplateDocument(name, version.content(),
                        version.publishedAt() == null ? -1 : version.publishedAt().toEpochMilli(),
                        TemplateOrigin.STORE, version.version()));
    }

    /**
     * The text as a built-in top-level template is read: lines joined by {@code \n}, without a
     * final line break, so an override with the same text renders exactly like the file.
     * Includes are used as stored, as built-in includes are.
     */
    static String normaliseLineBreaks(String content) {
        return content.lines().collect(Collectors.joining("\n"));
    }
}
