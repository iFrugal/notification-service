package com.lazydevs.notification.api.template;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Identifies one template in one layer (DD-38): a tenant, or the default layer, plus the
 * channel, the template id and a locale.
 *
 * <p>The default layer is {@code tenantId == null}. The tenant id {@value #DEFAULT_TENANT}
 * names the same layer, because it is the directory of the fallback templates, so it is
 * normalised to {@code null}. Channels are lower case ({@code email}, {@code sms},
 * {@code whatsapp}, {@code push}). The locale is reserved: the engine looks up only the
 * root locale {@code ""}.
 *
 * <p>Every part is a single safe path segment: 1-128 letters, digits, {@code .}, {@code _}
 * or {@code -}, starting with a letter or digit, without {@code ..}. That is the rule the
 * engine applies to template ids since 1.1.2, so a key always maps to a template name
 * under the base path.
 *
 * @param tenantId   tenant, or {@code null} for the default layer
 * @param channel    channel, lower case
 * @param templateId template id
 * @param locale     locale tag, {@code ""} for the root locale
 * @since 1.3.0
 */
public record TemplateKey(String tenantId, String channel, String templateId, String locale) {

    /** Tenant directory that holds the templates every tenant falls back to. */
    public static final String DEFAULT_TENANT = "default";

    /** File extension of a template name. */
    public static final String EXTENSION = ".ftl";

    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    /**
     * Normalises and validates the parts.
     *
     * @throws IllegalArgumentException when a part is missing or not a safe path segment
     */
    public TemplateKey {
        tenantId = tenantId == null || tenantId.isBlank() || DEFAULT_TENANT.equals(tenantId) ? null : tenantId;
        channel = channel == null ? null : channel.toLowerCase(Locale.ROOT);
        locale = locale == null ? "" : locale;
        if (tenantId != null) {
            require("tenantId", tenantId);
        }
        require("channel", channel);
        require("templateId", templateId);
        if (!locale.isEmpty()) {
            require("locale", locale);
        }
    }

    /** Key for the root locale. */
    public static TemplateKey of(String tenantId, String channel, String templateId) {
        return new TemplateKey(tenantId, channel, templateId, "");
    }

    /**
     * The key a template name of the form {@code <tenant>/<channel>/<templateId>.ftl} stands
     * for, as used by {@code <#include>}; empty for any other name.
     */
    public static Optional<TemplateKey> fromName(String name) {
        if (name == null || !name.endsWith(EXTENSION)) {
            return Optional.empty();
        }
        String[] parts = name.substring(0, name.length() - EXTENSION.length()).split("/", -1);
        if (parts.length != 3 || !isValidSegment(parts[0]) || !isValidSegment(parts[1])
                || !isValidSegment(parts[2]) || !parts[1].equals(parts[1].toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(of(parts[0], parts[1], parts[2]));
    }

    /**
     * Whether {@code value} is a safe single path segment: 1-128 letters, digits, '.', '_'
     * or '-', starting with a letter or digit, without "..".
     */
    public static boolean isValidSegment(String value) {
        return value != null
                && SAFE_SEGMENT.matcher(value).matches()
                && !value.contains("..");
    }

    /** Whether this key names the default layer. */
    @JsonIgnore
    public boolean isDefaultLayer() {
        return tenantId == null;
    }

    /** The same template in the default layer. */
    public TemplateKey defaultLayer() {
        return isDefaultLayer() ? this : new TemplateKey(null, channel, templateId, locale);
    }

    /** The same template in another tenant's layer ({@code null} for the default layer). */
    public TemplateKey withTenant(String tenant) {
        return new TemplateKey(tenant, channel, templateId, locale);
    }

    /** Directory of this layer under the base path: the tenant id, or {@value #DEFAULT_TENANT}. */
    public String tenantDirectory() {
        return tenantId == null ? DEFAULT_TENANT : tenantId;
    }

    /**
     * Template name relative to the base path, {@code <tenant>/<channel>/<templateId>.ftl}.
     * Relative includes resolve against it, whichever source served the template.
     */
    public String name() {
        return tenantDirectory() + "/" + channel + "/" + templateId + EXTENSION;
    }

    private static void require(String field, String value) {
        if (!isValidSegment(value)) {
            throw new IllegalArgumentException("Invalid template key: " + field
                    + " must be 1-128 letters, digits, '.', '_' or '-', start with a letter or digit,"
                    + " and not contain two consecutive dots");
        }
    }
}
