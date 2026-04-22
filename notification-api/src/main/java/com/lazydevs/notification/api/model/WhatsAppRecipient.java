package com.lazydevs.notification.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Map;

/**
 * WhatsApp recipient details.
 *
 * @param id                   optional recipient identifier for tracking
 * @param phoneNumber          phone number in E.164 format
 * @param whatsappTemplateName WhatsApp-approved template name (required for business messaging)
 * @param languageCode         language code for the template, e.g. {@code "en"} or {@code "en_US"}
 * @param headerParameters     template header parameters (for templates with header variables)
 * @param bodyParameters       template body parameters (for templates with body variables)
 * @param buttonParameters     template button parameters (for templates with button variables)
 */
public record WhatsAppRecipient(
        String id,
        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^\\+[1-9]\\d{1,14}$",
                message = "Phone number must be in E.164 format")
        String phoneNumber,
        String whatsappTemplateName,
        String languageCode,
        List<String> headerParameters,
        List<String> bodyParameters,
        List<Map<String, String>> buttonParameters) implements Recipient {

    @Override
    public String channelType() {
        return "WHATSAPP";
    }
}
