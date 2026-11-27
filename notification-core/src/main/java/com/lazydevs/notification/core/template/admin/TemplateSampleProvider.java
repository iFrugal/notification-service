package com.lazydevs.notification.core.template.admin;

import com.lazydevs.notification.api.template.TemplateKey;

import java.util.Map;
import java.util.Optional;

/**
 * Seeds the sample data model of a template that has no stored one yet, for example from
 * fixtures kept next to the templates (DD-38). Samples are shown to editors: they must be
 * made-up data, never real recipients.
 *
 * @since 1.3.0
 */
@FunctionalInterface
public interface TemplateSampleProvider {

    /** A provider without samples. */
    TemplateSampleProvider NONE = key -> Optional.empty();

    /** The sample model for {@code key}, empty when there is none. */
    Optional<Map<String, Object>> sample(TemplateKey key);
}
