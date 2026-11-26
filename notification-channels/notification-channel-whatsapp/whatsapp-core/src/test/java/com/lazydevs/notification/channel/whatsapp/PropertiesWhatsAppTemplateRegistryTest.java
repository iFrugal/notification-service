package com.lazydevs.notification.channel.whatsapp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertiesWhatsAppTemplateRegistryTest {

    static WhatsAppProperties.Template template(String name, String language, List<String> body) {
        WhatsAppProperties.Template template = new WhatsAppProperties.Template();
        template.setName(name);
        template.setLanguage(language);
        template.setBody(new ArrayList<>(body));
        return template;
    }

    static WhatsAppProperties.Button button(Integer index, String subType, String variable) {
        WhatsAppProperties.Button button = new WhatsAppProperties.Button();
        button.setIndex(index);
        button.setSubType(subType);
        button.setVariable(variable);
        return button;
    }

    private static WhatsAppProperties properties(String tenant, String id, WhatsAppProperties.Template template) {
        WhatsAppProperties properties = new WhatsAppProperties();
        Map<String, WhatsAppProperties.Template> byId = new LinkedHashMap<>();
        byId.put(id, template);
        properties.getTemplates().put(tenant, byId);
        return properties;
    }

    @Test
    void tenantEntryWins_thenDefaultTenant_thenNothing() {
        WhatsAppProperties properties = properties("default", "order-shipped",
                template("order_shipped", "en", List.of("customerName")));
        properties.getTemplates().put("acme", new LinkedHashMap<>(Map.of("order-shipped",
                template("acme_order_shipped", "en_US", List.of("customerName", "orderNumber")))));
        PropertiesWhatsAppTemplateRegistry registry = new PropertiesWhatsAppTemplateRegistry(properties);

        assertThat(registry.find("acme", "order-shipped")).get()
                .extracting(WhatsAppTemplateBinding::name).isEqualTo("acme_order_shipped");
        assertThat(registry.find("globex", "order-shipped")).get()
                .extracting(WhatsAppTemplateBinding::name).isEqualTo("order_shipped");
        assertThat(registry.find(null, "order-shipped")).isPresent();
        assertThat(registry.find("acme", "unknown")).isEmpty();
        assertThat(registry.find("acme", null)).isEmpty();
        assertThat(registry.bindings()).containsOnlyKeys("default", "acme");
    }

    @Test
    void buildsButtons_normalisingTheSubType() {
        WhatsAppProperties.Template template = template("order_shipped", "en", List.of());
        template.getButtons().add(button(0, "Quick-Reply", "trackingToken"));
        WhatsAppTemplateBinding binding = new PropertiesWhatsAppTemplateRegistry(
                properties("default", "order-shipped", template)).find("x", "order-shipped").orElseThrow();

        assertThat(binding.buttons()).containsExactly(
                new WhatsAppTemplateBinding.Button(0, "quick_reply", "trackingToken"));
        assertThat(binding.header()).isEmpty();
    }

    @Test
    void incompleteBindings_failConstruction_namingTheProperty() {
        assertThatThrownBy(() -> new PropertiesWhatsAppTemplateRegistry(
                properties("acme", "order-shipped", template(null, "en", List.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notification.whatsapp.templates.acme.order-shipped")
                .hasMessageContaining("name");
        assertThatThrownBy(() -> new PropertiesWhatsAppTemplateRegistry(
                properties("acme", "x", template("x", " ", List.of()))))
                .hasMessageContaining("language");
        assertThatThrownBy(() -> new PropertiesWhatsAppTemplateRegistry(
                properties("acme", "x", template("x", "en", List.of("ok", " ")))))
                .hasMessageContaining("body variable 2");
        WhatsAppProperties.Template badButton = template("x", "en", List.of());
        badButton.getButtons().add(button(10, "url", "v"));
        assertThatThrownBy(() -> new PropertiesWhatsAppTemplateRegistry(properties("acme", "x", badButton)))
                .hasMessageContaining("button index");
        WhatsAppProperties.Template noIndex = template("x", "en", List.of());
        noIndex.getButtons().add(button(null, "url", "v"));
        assertThatThrownBy(() -> new PropertiesWhatsAppTemplateRegistry(properties("acme", "x", noIndex)))
                .hasMessageContaining("button index is required");
        assertThatThrownBy(() -> new PropertiesWhatsAppTemplateRegistry(properties("acme", "x", null)))
                .hasMessageContaining("is empty");
    }

    @Test
    void emptyProperties_findNothing() {
        assertThat(new PropertiesWhatsAppTemplateRegistry(new WhatsAppProperties()).find("a", "b")).isEmpty();
        assertThat(new PropertiesWhatsAppTemplateRegistry(null).bindings()).isEmpty();
    }
}
