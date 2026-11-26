package com.lazydevs.notification.channel.whatsapp;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.TemplateNotFoundException;
import com.lazydevs.notification.api.exception.TemplateValidationException;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.WhatsAppRecipient;
import com.lazydevs.notification.channel.whatsapp.WhatsAppTemplateMessage.ButtonParameter;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhatsAppContentRendererTest {

    private static final String PHONE = "+15550100001";

    private static final WhatsAppTemplateBinding ORDER_SHIPPED = new WhatsAppTemplateBinding(
            "order_shipped", "en", List.of("carrier"), List.of("customerName", "orderNumber"),
            List.of(new WhatsAppTemplateBinding.Button(0, "url", "trackingToken")));

    private final WhatsAppTemplateRegistry registry = (tenant, id) ->
            "order-shipped".equals(id) ? Optional.of(ORDER_SHIPPED) : Optional.empty();

    private static NotificationRequest request(WhatsAppRecipient recipient, String type, Map<String, Object> data) {
        return NotificationRequest.builder()
                .requestId("req-1")
                .tenantId("acme")
                .notificationType(type)
                .channel(Channel.WHATSAPP)
                .recipient(recipient)
                .templateData(data)
                .build();
    }

    private static WhatsAppRecipient plain() {
        return new WhatsAppRecipient(null, PHONE, null, null, null, null, null);
    }

    private static Map<String, Object> orderData() {
        Map<String, Object> data = new HashMap<>();
        data.put("carrier", "Parcel Co");
        data.put("customerName", "Jane");
        data.put("orderNumber", 1042);
        data.put("trackingToken", "abc123");
        return data;
    }

    @Test
    void binding_resolvesVariablesInPlaceholderOrder() {
        WhatsAppContentRenderer renderer = new WhatsAppContentRenderer(registry, null);

        RenderedContent content = renderer.render(request(plain(), "order-shipped", orderData()));

        assertThat(renderer.channel()).isEqualTo(Channel.WHATSAPP);
        assertThat(content.templateId()).isEqualTo("order-shipped");
        WhatsAppTemplateMessage message = content.payload(WhatsAppTemplateMessage.class).orElseThrow();
        assertThat(message.name()).isEqualTo("order_shipped");
        assertThat(message.language()).isEqualTo("en");
        assertThat(message.headerParameters()).containsExactly("Parcel Co");
        assertThat(message.bodyParameters()).containsExactly("Jane", "1042");
        assertThat(message.buttons()).containsExactly(new ButtonParameter(0, "url", "abc123"));
        assertThat(message.toString()).doesNotContain("Jane", "abc123");
    }

    @Test
    void explicitTemplateId_winsOverNotificationType() {
        NotificationRequest request = request(plain(), "SOMETHING_ELSE", orderData());
        request.setTemplateId("order-shipped");

        RenderedContent content = new WhatsAppContentRenderer(registry, null).render(request);

        assertThat(content.payload(WhatsAppTemplateMessage.class)).isPresent();
    }

    @Test
    void missingOrBadVariable_isTemplateValidationException() {
        Map<String, Object> data = orderData();
        data.remove("orderNumber");
        WhatsAppContentRenderer renderer = new WhatsAppContentRenderer(registry, null);
        NotificationRequest missing = request(plain(), "order-shipped", data);

        assertThatThrownBy(() -> renderer.render(missing))
                .isInstanceOf(TemplateValidationException.class)
                .hasMessageContaining("orderNumber");

        Map<String, Object> multiline = orderData();
        multiline.put("customerName", "Jane\nDoe");
        NotificationRequest bad = request(plain(), "order-shipped", multiline);
        assertThatThrownBy(() -> renderer.render(bad)).isInstanceOf(TemplateValidationException.class);

        NotificationRequest noData = request(plain(), "order-shipped", null);
        assertThatThrownBy(() -> renderer.render(noData)).isInstanceOf(TemplateValidationException.class);
    }

    @Test
    void recipientWithTemplateName_bypassesTheRegistry() {
        WhatsAppTemplateRegistry failing = (tenant, id) -> {
            throw new AssertionError("registry must not be consulted");
        };
        WhatsAppRecipient recipient = new WhatsAppRecipient(null, PHONE, " legacy_template ", "en_US",
                List.of("Header"), List.of("one", "two"),
                List.of(Map.of("index", "1", "sub_type", "QUICK_REPLY", "payload", "yes"),
                        Map.of("text", "suffix")));

        RenderedContent content = new WhatsAppContentRenderer(failing, null)
                .render(request(recipient, "order-shipped", Map.of()));

        WhatsAppTemplateMessage message = content.payload(WhatsAppTemplateMessage.class).orElseThrow();
        assertThat(content.templateId()).isEqualTo("legacy_template");
        assertThat(message.name()).isEqualTo("legacy_template");
        assertThat(message.language()).isEqualTo("en_US");
        assertThat(message.headerParameters()).containsExactly("Header");
        assertThat(message.bodyParameters()).containsExactly("one", "two");
        assertThat(message.buttons()).containsExactly(
                new ButtonParameter(1, "quick_reply", "yes"),
                new ButtonParameter(1, "url", "suffix"));
    }

    @Test
    void recipientWithTemplateName_isValidated() {
        WhatsAppContentRenderer renderer = new WhatsAppContentRenderer(registry, null);
        WhatsAppRecipient noLanguage = new WhatsAppRecipient(null, PHONE, "t", null, null, null, null);
        WhatsAppRecipient badBody = new WhatsAppRecipient(null, PHONE, "t", "en", null, List.of("a\tb"), null);
        WhatsAppRecipient badIndex = new WhatsAppRecipient(null, PHONE, "t", "en", null, null,
                List.of(Map.of("index", "x", "text", "v")));
        WhatsAppRecipient outOfRange = new WhatsAppRecipient(null, PHONE, "t", "en", null, null,
                List.of(Map.of("index", "12", "text", "v")));
        WhatsAppRecipient noValue = new WhatsAppRecipient(null, PHONE, "t", "en", null, null,
                List.of(Map.of("index", "0")));

        for (WhatsAppRecipient recipient : List.of(noLanguage, badBody, badIndex, outOfRange, noValue)) {
            NotificationRequest request = request(recipient, "x", Map.of());
            assertThatThrownBy(() -> renderer.render(request)).isInstanceOf(TemplateValidationException.class);
        }
    }

    @Test
    void neitherTemplateNameNorBinding_fallsBack_orIsNotFound() {
        RenderedContent text = RenderedContent.text("hello");
        NotificationRequest request = request(plain(), "WELCOME", Map.of());

        assertThat(new WhatsAppContentRenderer(registry, r -> text).render(request)).isSameAs(text);
        WhatsAppContentRenderer noFallback = new WhatsAppContentRenderer(registry, null);
        assertThatThrownBy(() -> noFallback.render(request))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasMessageContaining("WELCOME")
                .hasMessageContaining("acme");
    }
}
