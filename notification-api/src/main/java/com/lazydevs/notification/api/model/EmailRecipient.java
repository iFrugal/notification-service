package com.lazydevs.notification.api.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Email recipient details.
 *
 * @param id      optional recipient identifier for tracking
 * @param to      primary recipient email address (required)
 * @param cc      CC recipient addresses (optional)
 * @param bcc     BCC recipient addresses (optional)
 * @param replyTo reply-to address (optional)
 * @param subject email subject (may be overridden by the rendered template)
 */
public record EmailRecipient(
        String id,
        @NotBlank(message = "Email 'to' address is required")
        @Email(message = "Invalid email address format")
        String to,
        List<@Email String> cc,
        List<@Email String> bcc,
        @Email String replyTo,
        String subject) implements Recipient {

    @Override
    public String channelType() {
        return "EMAIL";
    }
}
