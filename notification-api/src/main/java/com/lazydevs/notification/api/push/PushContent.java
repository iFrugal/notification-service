package com.lazydevs.notification.api.push;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Push content rendered from a template with {@code [TITLE]}, {@code [BODY]} and
 * {@code [DATA key="..."]} sections (DD-37), carried as
 * {@link com.lazydevs.notification.api.channel.RenderedContent#payload()}.
 *
 * <p>The title and body are also the content's {@code subject} and {@code textBody}, so a
 * provider that only reads those keeps working. A provider merges {@link #data()} under the
 * recipient's own data: a key the caller set wins.
 *
 * @param title the rendered title, {@code null} without a {@code [TITLE]} section
 * @param body  the rendered body, may be {@code null}
 * @param data  the rendered data sections by key, in template order, never {@code null}
 * @since 1.4.0
 */
public record PushContent(String title, String body, Map<String, String> data) {

    public PushContent {
        data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}
