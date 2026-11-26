package com.lazydevs.notification.channel.whatsapp;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.TemplateNotFoundException;
import com.lazydevs.notification.api.exception.TemplateValidationException;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.WhatsAppRecipient;
import com.lazydevs.notification.channel.whatsapp.WhatsAppTemplateMessage.ButtonParameter;
import com.lazydevs.notification.core.template.ChannelContentRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The {@code WHATSAPP} {@link ChannelContentRenderer} (DD-35): turns a request
 * into a {@link WhatsAppTemplateMessage}, the payload of the returned
 * {@link RenderedContent}.
 *
 * <p>In order:
 * <ol>
 *   <li>A {@link WhatsAppRecipient} that names a template
 *       ({@code whatsappTemplateName}) is sent as it says: its language and
 *       header, body and button parameters, without the registry. Each
 *       {@code buttonParameters} entry is a map with {@code index} (defaults to
 *       the entry's position), {@code sub_type} (defaults to {@code url}) and
 *       the value under {@code text}, {@code payload}, {@code coupon_code} or
 *       {@code value}.</li>
 *   <li>Otherwise the {@link WhatsAppTemplateRegistry} binding of the
 *       request's {@code templateId}, else its {@code notificationType}, for
 *       the request's tenant or the default tenant; each named variable is
 *       read from {@code templateData}.</li>
 *   <li>Otherwise the fallback renderer, the FreeMarker template engine in a
 *       Spring context, which keeps the 1.2 behaviour for applications that
 *       render WhatsApp text themselves. Without a fallback this is
 *       {@link TemplateNotFoundException}.</li>
 * </ol>
 * Every parameter passes {@link WhatsAppParameterRules}; a bad one is a
 * {@link TemplateValidationException} and the provider is never called.
 *
 * @since 1.4.0
 */
public class WhatsAppContentRenderer implements ChannelContentRenderer {

    /** Button sub type used when a recipient's button entry names none. */
    public static final String DEFAULT_BUTTON_SUB_TYPE = "url";

    private static final List<String> BUTTON_VALUE_KEYS = List.of("text", "payload", "coupon_code", "value");
    private static final List<String> BUTTON_SUB_TYPE_KEYS = List.of("sub_type", "subType", "sub-type");
    private static final String BUTTON_INDEX_KEY = "index";

    private final WhatsAppTemplateRegistry registry;
    private final Function<NotificationRequest, RenderedContent> fallback;

    /**
     * @param registry the template bindings
     * @param fallback renders a request that neither names a template nor has a
     *                 binding, typically the template engine; {@code null} for none
     */
    public WhatsAppContentRenderer(WhatsAppTemplateRegistry registry,
                                   Function<NotificationRequest, RenderedContent> fallback) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.fallback = fallback;
    }

    @Override
    public Channel channel() {
        return Channel.WHATSAPP;
    }

    @Override
    public RenderedContent render(NotificationRequest request) {
        if (request.getRecipient() instanceof WhatsAppRecipient recipient
                && hasText(recipient.whatsappTemplateName())) {
            return content(recipient.whatsappTemplateName().strip(), fromRecipient(recipient));
        }
        String templateId = hasText(request.getTemplateId()) ? request.getTemplateId() : request.getNotificationType();
        WhatsAppTemplateBinding binding = registry.find(request.getTenantId(), templateId).orElse(null);
        if (binding != null) {
            return content(templateId, fromBinding(templateId, binding, request.getTemplateData()));
        }
        if (fallback != null) {
            return fallback.apply(request);
        }
        throw new TemplateNotFoundException("No WhatsApp template binding for tenant '" + request.getTenantId()
                + "', template '" + templateId + "' (notification.whatsapp.templates." + request.getTenantId()
                + "." + templateId + " or notification.whatsapp.templates." + WhatsAppTemplateRegistry.DEFAULT_TENANT
                + "." + templateId + ")");
    }

    private static RenderedContent content(String templateId, WhatsAppTemplateMessage message) {
        return new RenderedContent(null, null, null, templateId, message);
    }

    static WhatsAppTemplateMessage fromBinding(String templateId, WhatsAppTemplateBinding binding,
                                               Map<String, Object> data) {
        Map<String, Object> values = data == null ? Map.of() : data;
        List<String> header = binding.header().stream()
                .map(variable -> WhatsAppParameterRules.check(templateId, variable, values.get(variable)))
                .toList();
        List<String> body = binding.body().stream()
                .map(variable -> WhatsAppParameterRules.check(templateId, variable, values.get(variable)))
                .toList();
        List<ButtonParameter> buttons = binding.buttons().stream()
                .map(button -> new ButtonParameter(button.index(), button.subType(),
                        WhatsAppParameterRules.check(templateId, button.variable(), values.get(button.variable()))))
                .toList();
        return new WhatsAppTemplateMessage(binding.name(), binding.language(), header, body, buttons);
    }

    static WhatsAppTemplateMessage fromRecipient(WhatsAppRecipient recipient) {
        String name = recipient.whatsappTemplateName().strip();
        if (!hasText(recipient.languageCode())) {
            throw new TemplateValidationException("WhatsApp template '" + name
                    + "': languageCode is required with whatsappTemplateName");
        }
        List<String> header = positional(name, "header", recipient.headerParameters());
        List<String> body = positional(name, "body", recipient.bodyParameters());
        List<ButtonParameter> buttons = new ArrayList<>();
        List<Map<String, String>> entries = recipient.buttonParameters() == null
                ? List.of() : recipient.buttonParameters();
        for (int i = 0; i < entries.size(); i++) {
            buttons.add(button(name, i, entries.get(i)));
        }
        return new WhatsAppTemplateMessage(name, recipient.languageCode().strip(), header, body, buttons);
    }

    private static List<String> positional(String name, String section, List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> checked = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            checked.add(WhatsAppParameterRules.check(name, section + "[" + i + "]", values.get(i)));
        }
        return checked;
    }

    private static ButtonParameter button(String name, int position, Map<String, String> entry) {
        String variable = "button[" + position + "]";
        Map<String, String> fields = entry == null ? Map.of() : entry;
        int index = position;
        String rawIndex = fields.get(BUTTON_INDEX_KEY);
        if (hasText(rawIndex)) {
            try {
                index = Integer.parseInt(rawIndex.strip());
            } catch (NumberFormatException e) {
                throw new TemplateValidationException("WhatsApp template '" + name + "': " + variable
                        + " index is not a number");
            }
        }
        if (index < 0 || index > WhatsAppTemplateBinding.Button.MAX_INDEX) {
            throw new TemplateValidationException("WhatsApp template '" + name + "': " + variable
                    + " index must be 0 to " + WhatsAppTemplateBinding.Button.MAX_INDEX);
        }
        String subType = firstText(fields, BUTTON_SUB_TYPE_KEYS);
        String value = WhatsAppParameterRules.check(name, variable, firstText(fields, BUTTON_VALUE_KEYS));
        return new ButtonParameter(index,
                WhatsAppTemplateBinding.normaliseSubType(subType == null ? DEFAULT_BUTTON_SUB_TYPE : subType), value);
    }

    private static String firstText(Map<String, String> fields, List<String> keys) {
        for (String key : keys) {
            String value = fields.get(key);
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
