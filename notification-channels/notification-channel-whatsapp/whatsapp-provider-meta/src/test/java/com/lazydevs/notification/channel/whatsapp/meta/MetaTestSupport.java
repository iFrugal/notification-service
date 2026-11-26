package com.lazydevs.notification.channel.whatsapp.meta;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.WhatsAppRecipient;
import com.lazydevs.notification.channel.whatsapp.WhatsAppTemplateMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared fixtures. Phone numbers are from the reserved 555-01xx range. */
final class MetaTestSupport {

    static final String PHONE = "+15550100001";

    private MetaTestSupport() {
    }

    static NotificationRequest request() {
        return NotificationRequest.builder()
                .requestId("req-1")
                .tenantId("default")
                .notificationType("order-shipped")
                .channel(Channel.WHATSAPP)
                .recipient(new WhatsAppRecipient(null, PHONE, null, null, null, null, null))
                .templateData(Map.of())
                .build();
    }

    static WhatsAppTemplateMessage message() {
        return new WhatsAppTemplateMessage("order_shipped", "en", List.of(), List.of("Jane", "1042"), List.of());
    }

    static RenderedContent content() {
        return new RenderedContent(null, null, null, "order-shipped", message());
    }

    static MetaWhatsAppProvider provider(MetaStubServer stub, Map<String, Object> extra) {
        MetaWhatsAppProvider provider = new MetaWhatsAppProvider(null, null);
        Map<String, Object> props = new LinkedHashMap<>(stub.properties());
        props.putAll(extra);
        provider.configure(props);
        provider.init();
        return provider;
    }
}
