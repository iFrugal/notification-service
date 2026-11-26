package com.lazydevs.notification.channel.whatsapp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The default {@link WhatsAppTemplateRegistry}: the bindings under
 * {@code notification.whatsapp.templates} (see {@link WhatsAppProperties}).
 *
 * <p>Every binding is validated when the registry is built, so a typo in the
 * YAML fails the startup instead of the first send.
 *
 * @since 1.3.0
 */
public class PropertiesWhatsAppTemplateRegistry implements WhatsAppTemplateRegistry {

    private final Map<String, Map<String, WhatsAppTemplateBinding>> bindings;

    /**
     * @param properties the bound properties
     * @throws IllegalStateException when a binding is incomplete, naming the tenant and the template id
     */
    public PropertiesWhatsAppTemplateRegistry(WhatsAppProperties properties) {
        Map<String, Map<String, WhatsAppTemplateBinding>> byTenant = new LinkedHashMap<>();
        if (properties != null && properties.getTemplates() != null) {
            properties.getTemplates().forEach((tenant, templates) -> {
                Map<String, WhatsAppTemplateBinding> byId = new LinkedHashMap<>();
                if (templates != null) {
                    templates.forEach((id, template) -> byId.put(id, toBinding(tenant, id, template)));
                }
                byTenant.put(tenant, Collections.unmodifiableMap(byId));
            });
        }
        this.bindings = Collections.unmodifiableMap(byTenant);
    }

    @Override
    public Optional<WhatsAppTemplateBinding> find(String tenantId, String templateId) {
        if (templateId == null) {
            return Optional.empty();
        }
        WhatsAppTemplateBinding own = tenantId == null ? null : bindings.getOrDefault(tenantId, Map.of()).get(templateId);
        if (own != null) {
            return Optional.of(own);
        }
        return Optional.ofNullable(bindings.getOrDefault(DEFAULT_TENANT, Map.of()).get(templateId));
    }

    /** @return every binding, by tenant id and then logical template id */
    public Map<String, Map<String, WhatsAppTemplateBinding>> bindings() {
        return bindings;
    }

    private static WhatsAppTemplateBinding toBinding(String tenant, String id, WhatsAppProperties.Template template) {
        String where = WhatsAppProperties.PREFIX + ".templates." + tenant + "." + id;
        if (template == null) {
            throw new IllegalStateException(where + " is empty");
        }
        try {
            List<WhatsAppProperties.Button> buttons = template.getButtons() == null ? List.of() : template.getButtons();
            return new WhatsAppTemplateBinding(template.getName(), template.getLanguage(), template.getHeader(),
                    template.getBody(), buttons.stream().map(PropertiesWhatsAppTemplateRegistry::toButton).toList());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(where + ": " + e.getMessage(), e);
        }
    }

    private static WhatsAppTemplateBinding.Button toButton(WhatsAppProperties.Button button) {
        if (button == null || button.getIndex() == null) {
            throw new IllegalArgumentException("button index is required");
        }
        return new WhatsAppTemplateBinding.Button(button.getIndex(), button.getSubType(), button.getVariable());
    }
}
