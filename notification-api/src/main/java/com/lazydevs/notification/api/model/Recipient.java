package com.lazydevs.notification.api.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Base class for notification recipients.
 * Uses Jackson polymorphic deserialization based on channel type.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = EmailRecipient.class, name = "EMAIL"),
    @JsonSubTypes.Type(value = SmsRecipient.class, name = "SMS"),
    @JsonSubTypes.Type(value = WhatsAppRecipient.class, name = "WHATSAPP"),
    @JsonSubTypes.Type(value = PushRecipient.class, name = "PUSH")
})
public abstract class Recipient {

    /**
     * Optional recipient identifier for tracking purposes
     */
    private String id;

    /**
     * Get the channel type for this recipient
     */
    public abstract String getChannelType();
}
