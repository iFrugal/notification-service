package com.lazydevs.notification.channel.whatsapp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code notification.whatsapp.*}: the YAML template mapping read by
 * {@link PropertiesWhatsAppTemplateRegistry} (DD-35).
 *
 * <pre>
 * notification:
 *   whatsapp:
 *     templates:
 *       default:                  # tenant id, or "default" for every tenant
 *         order-shipped:          # logical id: request.templateId, else notificationType
 *           name: order_shipped   # name approved at the provider
 *           language: en
 *           header: []
 *           body: [customerName, orderNumber]
 *           buttons:
 *             - index: 0
 *               sub-type: url
 *               variable: trackingToken
 * </pre>
 *
 * @since 1.4.0
 */
@Data
@ConfigurationProperties(prefix = WhatsAppProperties.PREFIX)
public class WhatsAppProperties {

    /** Property prefix. */
    public static final String PREFIX = "notification.whatsapp";

    /** Template bindings by tenant id, then by logical template id. */
    private Map<String, Map<String, Template>> templates = new LinkedHashMap<>();

    /** One template binding. */
    @Data
    public static class Template {

        /** Template name approved at the provider. */
        private String name;

        /** Template language code, for example {@code en} or {@code en_US}. */
        private String language;

        /** {@code templateData} keys filling the header text placeholders, in order. */
        private List<String> header = new ArrayList<>();

        /** {@code templateData} keys filling the body text placeholders, in order. */
        private List<String> body = new ArrayList<>();

        /** Dynamic button parameters. */
        private List<Button> buttons = new ArrayList<>();
    }

    /** The dynamic part of one button. */
    @Data
    public static class Button {

        /** The button's position in the template, 0 to 9. */
        private Integer index;

        /** Button sub type: {@code url}, {@code quick_reply} or {@code copy_code}. */
        private String subType;

        /** {@code templateData} key holding the value. */
        private String variable;
    }
}
