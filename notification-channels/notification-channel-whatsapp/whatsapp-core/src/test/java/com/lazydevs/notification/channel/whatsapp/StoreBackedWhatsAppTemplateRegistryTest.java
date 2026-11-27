package com.lazydevs.notification.channel.whatsapp;

import com.lazydevs.notification.api.template.TemplateApprovalStatus;
import com.lazydevs.notification.api.template.TemplateKey;
import com.lazydevs.notification.api.template.TemplateStore;
import com.lazydevs.notification.api.template.TemplateVersion;
import com.lazydevs.notification.core.template.store.InMemoryTemplateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-38: bindings published in the template store win over the YAML ones per level, are
 * only returned while sendable, and are cached until the change feed says otherwise.
 */
class StoreBackedWhatsAppTemplateRegistryTest {

    private static final String BINDING = """
            {"name": "%s", "language": "en", "body": ["customerName"],
             "buttons": [{"index": 0, "subType": "url", "variable": "token"}]}""";

    private final InMemoryTemplateStore memory = new InMemoryTemplateStore(10_000);
    private final AtomicInteger lookups = new AtomicInteger();
    private StoreBackedWhatsAppTemplateRegistry registry;

    @BeforeEach
    void setUp() {
        WhatsAppProperties properties = new WhatsAppProperties();
        properties.getTemplates().put("default", Map.of("ORDER", template("yaml_default"),
                "YAML_ONLY", template("yaml_only")));
        properties.getTemplates().put("acme", Map.of("ORDER", template("yaml_acme")));
        properties.getTemplates().put("acme corp", Map.of("ORDER", template("yaml_acme_corp")));
        TemplateStore counting = (TemplateStore) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{TemplateStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findPublished")) {
                        lookups.incrementAndGet();
                    }
                    return method.invoke(memory, args);
                });
        registry = new StoreBackedWhatsAppTemplateRegistry(counting, new PropertiesWhatsAppTemplateRegistry(properties),
                Duration.ofHours(1), 100);
    }

    private static WhatsAppProperties.Template template(String name) {
        WhatsAppProperties.Template template = new WhatsAppProperties.Template();
        template.setName(name);
        template.setLanguage("en");
        return template;
    }

    private void publish(String tenant, String id, String content) {
        TemplateKey key = TemplateKey.of(tenant, "whatsapp", id);
        long revision = memory.findDraft(key).map(TemplateVersion::revision).orElse(0L);
        TemplateVersion draft = memory.saveDraft(key, TemplateVersion.JSON, content, null, revision, "ann", null);
        memory.publish(key, draft.revision(), "ann", "n");
        registry.templateChanged(key);
    }

    private Optional<String> name(String tenant, String id) {
        return registry.find(tenant, id).map(WhatsAppTemplateBinding::name);
    }

    @Test
    void layersStoreOverYamlTenantThenDefault() {
        assertThat(name("acme", "ORDER")).contains("yaml_acme");
        assertThat(name("globex", "ORDER")).contains("yaml_default");

        publish(null, "ORDER", BINDING.formatted("store_default"));
        assertThat(name("acme", "ORDER")).as("tenant YAML beats default store").contains("yaml_acme");
        assertThat(name("globex", "ORDER")).contains("store_default");
        assertThat(name(null, "ORDER")).contains("store_default");

        publish("acme", "ORDER", BINDING.formatted("store_acme"));
        assertThat(name("acme", "ORDER")).contains("store_acme");
        assertThat(name("acme", "YAML_ONLY")).contains("yaml_only");
        assertThat(name("acme", "NONE")).isEmpty();
        assertThat(registry.find("acme", null)).isEmpty();
    }

    @Test
    void parsesTheFullBinding() {
        publish(null, "ORDER", BINDING.formatted("order_shipped"));

        WhatsAppTemplateBinding binding = registry.find("x", "ORDER").orElseThrow();

        assertThat(binding.language()).isEqualTo("en");
        assertThat(binding.header()).isEmpty();
        assertThat(binding.body()).containsExactly("customerName");
        assertThat(binding.buttons()).containsExactly(new WhatsAppTemplateBinding.Button(0, "url", "token"));
    }

    @Test
    void onlySendableBindingsAreReturnedAndNeverReplacedByYaml() {
        publish(null, "ORDER", BINDING.formatted("store_default"));
        TemplateKey key = TemplateKey.of(null, "whatsapp", "ORDER");

        for (TemplateApprovalStatus status : List.of(TemplateApprovalStatus.SUBMITTED, TemplateApprovalStatus.REJECTED,
                TemplateApprovalStatus.PAUSED, TemplateApprovalStatus.DISABLED, TemplateApprovalStatus.NOT_SUBMITTED)) {
            memory.updateApproval(key, 1, status, null, null, "sys");
            registry.templateChanged(key);
            assertThat(name("globex", "ORDER")).as(status.name()).isEmpty();
        }
        memory.updateApproval(key, 1, TemplateApprovalStatus.APPROVED, null, null, "sys");
        registry.templateChanged(key);
        assertThat(name("globex", "ORDER")).contains("store_default");
    }

    @Test
    void invalidStoredBindingIsNotSendable() {
        publish(null, "ORDER", "{\"name\": \"x\"}");
        assertThat(name("globex", "ORDER")).isEmpty();
        publish(null, "ORDER", "not json");
        assertThat(name("globex", "ORDER")).isEmpty();
        publish(null, "ORDER", "{\"name\":\"x\",\"language\":\"en\",\"buttons\":[{\"subType\":\"url\"}]}");
        assertThat(name("globex", "ORDER")).isEmpty();
        for (String notAnArray : List.of("\"body\": \"customerName\"", "\"header\": {}", "\"buttons\": 1")) {
            publish(null, "ORDER", "{\"name\":\"x\",\"language\":\"en\"," + notAnArray + "}");
            assertThat(name("globex", "ORDER")).as(notAnArray).isEmpty();
        }
        publish(null, "ORDER", "{\"name\":\"x\",\"language\":\"en\",\"body\":null}");
        assertThat(name("globex", "ORDER")).as("null is an empty list").contains("x");
    }

    @Test
    void lookupsAreCachedUntilTheFeedEvicts() {
        // One find looks up the tenant key, then the default key
        name("globex", "ORDER");
        name("globex", "ORDER");
        assertThat(lookups.get()).isEqualTo(2);

        registry.templateChanged(TemplateKey.of(null, "email", "ORDER"));
        name("globex", "ORDER");
        assertThat(lookups.get()).as("other channels do not evict").isEqualTo(2);

        registry.templateChanged(TemplateKey.of(null, "whatsapp", "ORDER"));
        name("globex", "ORDER");
        assertThat(lookups.get()).as("only the default key is looked up again").isEqualTo(3);

        registry.allTemplatesChanged();
        name("globex", "ORDER");
        assertThat(lookups.get()).isEqualTo(5);
    }

    @Test
    void aTenantThatCannotBeAStoreKeyStillGetsItsYamlBinding() {
        publish(null, "ORDER", BINDING.formatted("store_default"));

        assertThat(name("acme corp", "ORDER")).contains("yaml_acme_corp");
        assertThat(name("other corp", "ORDER")).contains("store_default");
    }

    @Test
    void idsThatCannotBeStoreKeysGoStraightToTheFallback() {
        assertThat(name("acme", "not a key")).isEmpty();
        assertThat(lookups.get()).isZero();
    }
}
