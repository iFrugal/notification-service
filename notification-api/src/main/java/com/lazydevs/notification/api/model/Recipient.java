package com.lazydevs.notification.api.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed supertype for notification recipients. Each channel has its own
 * record implementation which carries channel-specific fields (e.g. email
 * CC list, SMS phone number, WhatsApp template parameters).
 *
 * <p>Polymorphic JSON (de)serialization is driven by a {@code "type"}
 * property keyed to channel name.
 */
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
public sealed interface Recipient
        permits EmailRecipient, SmsRecipient, WhatsAppRecipient, PushRecipient {

    /**
     * @return optional recipient identifier for tracking purposes.
     */
    String id();

    /**
     * @return channel name this recipient is scoped to, e.g. {@code "EMAIL"}.
     */
    String channelType();
}
