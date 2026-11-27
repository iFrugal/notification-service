package com.lazydevs.notification.core.template.admin;

import com.lazydevs.notification.api.template.TemplateKey;
import com.lazydevs.notification.api.template.TemplateVersion;

import java.util.Map;

/**
 * Everything an editor needs about one template in one layer (DD-38).
 *
 * @param key              the template and layer
 * @param contentType      {@code freemarker} or {@code json}
 * @param editable         whether the channel is editable in this deployment
 * @param effectiveSource  what a send for this layer renders today
 * @param effectiveVersion the store version of {@code effectiveSource}, {@code null} for a built-in file
 * @param builtInContent   this layer's own built-in file, or {@code null}
 * @param inheritedSource  what this layer falls back to without its own store override
 * @param inheritedContent the content of {@code inheritedSource}, or {@code null}
 * @param published        this layer's published override with content, or {@code null}
 * @param draft            this layer's draft with content and sample model, or {@code null}
 * @param sampleModel      the draft's sample model, else the published version's, else the
 *                         host's seeded sample, else an empty model
 * @since 1.3.0
 */
public record TemplateDetail(TemplateKey key, String contentType, boolean editable,
                             TemplateLayerSource effectiveSource, Integer effectiveVersion,
                             String builtInContent, TemplateLayerSource inheritedSource, String inheritedContent,
                             TemplateVersion published, TemplateVersion draft, Map<String, Object> sampleModel) {
}
