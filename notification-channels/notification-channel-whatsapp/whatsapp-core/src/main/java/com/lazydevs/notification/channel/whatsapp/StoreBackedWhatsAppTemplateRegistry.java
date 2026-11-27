package com.lazydevs.notification.channel.whatsapp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lazydevs.notification.api.template.TemplateChangeListener;
import com.lazydevs.notification.api.template.TemplateKey;
import com.lazydevs.notification.api.template.TemplateStore;
import com.lazydevs.notification.api.template.TemplateVersion;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link WhatsAppTemplateRegistry} that reads bindings published in the template store
 * (DD-38) before the YAML ones (DD-35).
 *
 * <p>A binding is stored under channel {@code whatsapp} with content type {@code json}:
 * <pre>
 * { "name": "order_shipped", "language": "en", "header": [], "body": ["customerName", "orderNumber"],
 *   "buttons": [ { "index": 0, "subType": "url", "variable": "trackingToken" } ] }
 * </pre>
 * Lookup is tenant first, then the default tenant; at each level the store's published
 * binding wins over the YAML one. A stored binding is only returned while it is sendable:
 * the provider approved it, or approval is not tracked. Any other approval status (submitted,
 * rejected, paused), and a binding that does not parse, yields no binding at all, so the
 * send fails before it is claimed instead of reaching the provider with a template it would
 * reject; the YAML binding is not used in its place.
 *
 * <p>{@link #find} runs on every send, so store lookups are cached; the template change feed
 * drops changed keys through {@link #templateChanged}.
 *
 * @since 1.3.0
 */
@Slf4j
public class StoreBackedWhatsAppTemplateRegistry implements WhatsAppTemplateRegistry, TemplateChangeListener {

    /** Channel of WhatsApp bindings in the store. */
    public static final String CHANNEL = "whatsapp";

    private final TemplateStore store;
    private final WhatsAppTemplateRegistry fallback;
    private final Cache<TemplateKey, Stored> cache;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param fallback the YAML registry, or any other; a {@link PropertiesWhatsAppTemplateRegistry}
     *                 is consulted per level (tenant, then default), any other only after the store
     *                 has neither a tenant nor a default binding
     * @param ttl      how long a store lookup is cached; zero or negative keeps it until evicted
     * @param maxSize  most cached lookups; zero or negative is unbounded
     */
    public StoreBackedWhatsAppTemplateRegistry(TemplateStore store, WhatsAppTemplateRegistry fallback, Duration ttl,
                                               long maxSize) {
        this.store = Objects.requireNonNull(store, "store");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        Caffeine<Object, Object> builder = Caffeine.newBuilder();
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            builder.expireAfterWrite(ttl);
        }
        if (maxSize > 0) {
            builder.maximumSize(maxSize);
        }
        this.cache = builder.build();
    }

    @Override
    public Optional<WhatsAppTemplateBinding> find(String tenantId, String templateId) {
        if (templateId == null) {
            return Optional.empty();
        }
        if (!TemplateKey.isValidSegment(templateId)) {
            // Cannot be a store key
            return fallback.find(tenantId, templateId);
        }
        if (tenantId != null && !DEFAULT_TENANT.equals(tenantId)) {
            // A tenant id that cannot be a store key can still have a YAML binding
            if (TemplateKey.isValidSegment(tenantId)) {
                Stored own = stored(TemplateKey.of(tenantId, CHANNEL, templateId));
                if (own.exists()) {
                    return Optional.ofNullable(own.binding());
                }
            }
            Optional<WhatsAppTemplateBinding> yaml = exactYaml(tenantId, templateId);
            if (yaml.isPresent()) {
                return yaml;
            }
        }
        Stored defaults = stored(TemplateKey.of(null, CHANNEL, templateId));
        if (defaults.exists()) {
            return Optional.ofNullable(defaults.binding());
        }
        return fallback.find(tenantId, templateId);
    }

    /** The YAML binding of exactly this tenant, when the fallback is the YAML registry. */
    private Optional<WhatsAppTemplateBinding> exactYaml(String tenantId, String templateId) {
        if (fallback instanceof PropertiesWhatsAppTemplateRegistry yaml) {
            return Optional.ofNullable(yaml.bindings().getOrDefault(tenantId, Map.of()).get(templateId));
        }
        return Optional.empty();
    }

    private Stored stored(TemplateKey key) {
        return cache.get(key, this::load);
    }

    private Stored load(TemplateKey key) {
        Optional<TemplateVersion> published = store.findPublished(key);
        if (published.isEmpty()) {
            return Stored.ABSENT;
        }
        TemplateVersion version = published.get();
        if (!version.isSendable()) {
            log.debug("WhatsApp binding {} v{} is {} at the provider; not sendable", key.name(), version.version(),
                    version.approvalStatus());
            return Stored.UNSENDABLE;
        }
        try {
            return new Stored(true, parse(version.content()));
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("WhatsApp binding {} v{} in the template store is not valid: {}", key.name(), version.version(),
                    e.getMessage());
            return Stored.UNSENDABLE;
        }
    }

    /**
     * A stored binding.
     *
     * @throws IllegalArgumentException when a required value is missing
     */
    WhatsAppTemplateBinding parse(String content) throws JsonProcessingException {
        JsonNode node = json.readTree(content);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("a binding must be a JSON object");
        }
        JsonNode buttonNodes = node.path("buttons");
        if (!buttonNodes.isMissingNode() && !buttonNodes.isNull() && !buttonNodes.isArray()) {
            throw new IllegalArgumentException("buttons must be an array");
        }
        List<WhatsAppTemplateBinding.Button> buttons = new ArrayList<>();
        for (JsonNode button : buttonNodes) {
            if (!button.hasNonNull("index")) {
                throw new IllegalArgumentException("button index is required");
            }
            buttons.add(new WhatsAppTemplateBinding.Button(button.get("index").asInt(),
                    text(button, "subType", "sub-type", "sub_type"), text(button, "variable")));
        }
        return new WhatsAppTemplateBinding(text(node, "name"), text(node, "language"), strings(node, "header"),
                strings(node, "body"), buttons);
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            if (node.hasNonNull(name)) {
                return node.get(name).asText();
            }
        }
        return null;
    }

    /** A list of variable names; absent or {@code null} is empty, anything but an array is refused. */
    private static List<String> strings(JsonNode node, String field) {
        JsonNode array = node.path(field);
        if (array.isMissingNode() || array.isNull()) {
            return List.of();
        }
        if (!array.isArray()) {
            throw new IllegalArgumentException(field + " must be an array of variable names");
        }
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.isNull() ? null : value.asText()));
        return values;
    }

    @Override
    public void templateChanged(TemplateKey key) {
        if (CHANNEL.equals(key.channel().toLowerCase(Locale.ROOT))) {
            cache.invalidate(key);
        }
    }

    @Override
    public void allTemplatesChanged() {
        cache.invalidateAll();
    }

    /**
     * A cached store lookup: {@code exists} when the store has a published binding;
     * {@code binding} is {@code null} when that binding is not sendable.
     */
    private record Stored(boolean exists, WhatsAppTemplateBinding binding) {
        static final Stored ABSENT = new Stored(false, null);
        static final Stored UNSENDABLE = new Stored(true, null);
    }
}
