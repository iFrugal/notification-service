package com.lazydevs.notification.api.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * Email recipient details.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class EmailRecipient extends Recipient {

    /**
     * Primary recipient email address
     */
    @NotBlank(message = "Email 'to' address is required")
    @Email(message = "Invalid email address format")
    private String to;

    /**
     * CC recipients (optional)
     */
    private List<@Email String> cc;

    /**
     * BCC recipients (optional)
     */
    private List<@Email String> bcc;

    /**
     * Reply-to address (optional)
     */
    @Email
    private String replyTo;

    /**
     * Email subject (can be overridden by template)
     */
    private String subject;

    @Override
    public String getChannelType() {
        return "EMAIL";
    }
}
