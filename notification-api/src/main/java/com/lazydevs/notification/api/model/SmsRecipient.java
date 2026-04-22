package com.lazydevs.notification.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * SMS recipient details.
 *
 * @param id          optional recipient identifier for tracking
 * @param phoneNumber phone number in E.164 format (e.g. {@code +1234567890})
 */
public record SmsRecipient(
        String id,
        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^\\+[1-9]\\d{1,14}$",
                message = "Phone number must be in E.164 format (e.g., +1234567890)")
        String phoneNumber) implements Recipient {

    @Override
    public String channelType() {
        return "SMS";
    }
}
