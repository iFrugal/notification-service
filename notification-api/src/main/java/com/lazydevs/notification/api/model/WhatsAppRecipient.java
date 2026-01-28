package com.lazydevs.notification.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Map;

/**
 * WhatsApp recipient details.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class WhatsAppRecipient extends Recipient {

    /**
     * Phone number in E.164 format (e.g., +1234567890)
     */
    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "Phone number must be in E.164 format")
    private String phoneNumber;

    /**
     * WhatsApp-approved template name (required for business messaging)
     */
    private String whatsappTemplateName;

    /**
     * Language code for the template (e.g., "en", "en_US")
     */
    private String languageCode;

    /**
     * Template header parameters (for templates with header variables)
     */
    private List<String> headerParameters;

    /**
     * Template body parameters (for templates with body variables)
     */
    private List<String> bodyParameters;

    /**
     * Template button parameters (for templates with button variables)
     */
    private List<Map<String, String>> buttonParameters;

    @Override
    public String getChannelType() {
        return "WHATSAPP";
    }
}
