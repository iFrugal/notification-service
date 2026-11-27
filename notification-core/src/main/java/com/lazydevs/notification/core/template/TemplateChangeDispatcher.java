package com.lazydevs.notification.core.template;

import com.lazydevs.notification.api.template.TemplateChangeFeed;
import com.lazydevs.notification.api.template.TemplateChangeListener;
import com.lazydevs.notification.api.template.TemplateKey;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Hands template changes to every {@link TemplateChangeListener} (DD-38): the engine, the
 * store-backed WhatsApp registry and any listener the host declares. Fed by the admin
 * service after a local publish and by each {@link TemplateChangeFeed} for changes made
 * elsewhere.
 * <p>
 * Not itself a listener, so collecting the listener beans never includes it. A failing
 * listener is logged and does not stop the others.
 *
 * @since 1.3.0
 */
@Slf4j
public class TemplateChangeDispatcher {

    private final Supplier<Stream<TemplateChangeListener>> listeners;

    public TemplateChangeDispatcher(Supplier<Stream<TemplateChangeListener>> listeners) {
        this.listeners = listeners;
    }

    /** Deliver the changes {@code feed} sees to the listeners. */
    public void listenTo(TemplateChangeFeed feed) {
        feed.subscribe(new TemplateChangeListener() {
            @Override
            public void templateChanged(TemplateKey key) {
                TemplateChangeDispatcher.this.templateChanged(key);
            }

            @Override
            public void allTemplatesChanged() {
                TemplateChangeDispatcher.this.allTemplatesChanged();
            }
        });
    }

    /** Tell every listener that {@code key} changed. */
    public void templateChanged(TemplateKey key) {
        listeners.get().forEach(listener -> {
            try {
                listener.templateChanged(key);
            } catch (RuntimeException e) {
                log.warn("Template change listener {} failed for {}: {}", listener.getClass().getName(),
                        ResourceLoaderTemplateSource.loggable(key.name()), e.toString());
            }
        });
    }

    /** Tell every listener that changes may have been missed. */
    public void allTemplatesChanged() {
        listeners.get().forEach(listener -> {
            try {
                listener.allTemplatesChanged();
            } catch (RuntimeException e) {
                log.warn("Template change listener {} failed to drop its cache: {}", listener.getClass().getName(),
                        e.toString());
            }
        });
    }
}
