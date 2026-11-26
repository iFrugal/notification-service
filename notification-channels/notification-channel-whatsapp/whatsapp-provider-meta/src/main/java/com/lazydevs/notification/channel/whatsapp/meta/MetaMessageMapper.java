package com.lazydevs.notification.channel.whatsapp.meta;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lazydevs.notification.channel.whatsapp.WhatsAppTemplateMessage;
import com.lazydevs.notification.channel.whatsapp.WhatsAppTemplateMessage.ButtonParameter;

import java.util.List;

/**
 * Builds the Cloud API {@code /messages} body for a template message:
 *
 * <pre>
 * {"messaging_product":"whatsapp","recipient_type":"individual","to":"15550100001","type":"template",
 *  "template":{"name":"order_shipped","language":{"code":"en"},"components":[
 *    {"type":"header","parameters":[{"type":"text","text":"..."}]},
 *    {"type":"body","parameters":[{"type":"text","text":"..."}]},
 *    {"type":"button","sub_type":"url","index":"0","parameters":[{"type":"text","text":"..."}]}]},
 *  "biz_opaque_callback_data":"&lt;request id&gt;"}
 * </pre>
 *
 * A component without parameters is left out, and so is {@code components}
 * when there is none. A {@code quick_reply} button carries a {@code payload}
 * parameter, a {@code copy_code} button a {@code coupon_code} one, any other
 * button a {@code text} one.
 */
final class MetaMessageMapper {

    /** Meta's limit for {@code biz_opaque_callback_data}. */
    static final int MAX_CALLBACK_DATA_LENGTH = 512;

    private static final String TYPE = "type";
    private static final String TEXT = "text";
    private static final String PARAMETERS = "parameters";

    private MetaMessageMapper() {
    }

    /**
     * @param to           the recipient, digits only (E.164 without the {@code +})
     * @param message      the template message
     * @param callbackData echoed in status webhooks as {@code biz_opaque_callback_data}; may be {@code null}
     * @return the request body
     */
    static ObjectNode body(String to, WhatsAppTemplateMessage message, String callbackData) {
        ObjectNode root = MetaJson.MAPPER.createObjectNode();
        root.put("messaging_product", "whatsapp");
        root.put("recipient_type", "individual");
        root.put("to", to);
        root.put(TYPE, "template");
        ObjectNode template = root.putObject("template");
        template.put("name", message.name());
        template.putObject("language").put("code", message.language());
        ArrayNode components = MetaJson.MAPPER.createArrayNode();
        textComponent(components, "header", message.headerParameters());
        textComponent(components, "body", message.bodyParameters());
        for (ButtonParameter button : message.buttons()) {
            ObjectNode component = components.addObject();
            component.put(TYPE, "button");
            component.put("sub_type", button.subType());
            component.put("index", Integer.toString(button.index()));
            ObjectNode parameter = component.putArray(PARAMETERS).addObject();
            switch (button.subType()) {
                case "quick_reply" -> parameter.put(TYPE, "payload").put("payload", button.value());
                case "copy_code" -> parameter.put(TYPE, "coupon_code").put("coupon_code", button.value());
                default -> parameter.put(TYPE, TEXT).put(TEXT, button.value());
            }
        }
        if (!components.isEmpty()) {
            template.set("components", components);
        }
        if (callbackData != null && !callbackData.isBlank() && callbackData.length() <= MAX_CALLBACK_DATA_LENGTH) {
            root.put("biz_opaque_callback_data", callbackData);
        }
        return root;
    }

    private static void textComponent(ArrayNode components, String type, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        ObjectNode component = components.addObject();
        component.put(TYPE, type);
        ArrayNode parameters = component.putArray(PARAMETERS);
        values.forEach(value -> parameters.addObject().put(TYPE, TEXT).put(TEXT, value));
    }
}
