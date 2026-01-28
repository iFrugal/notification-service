package com.lazydevs.notification.channel.sms.twilio;

import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.channel.SmsProvider;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.model.SmsRecipient;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Twilio SMS provider implementation.
 */
@Slf4j
public class TwilioSmsProvider implements SmsProvider {

    private String accountSid;
    private String authToken;
    private String fromNumber;
    private boolean initialized = false;

    @Override
    public String getProviderName() {
        return "twilio";
    }

    @Override
    public void configure(Map<String, Object> properties) {
        this.accountSid = getString(properties, "account-sid", getString(properties, "accountSid", null));
        this.authToken = getString(properties, "auth-token", getString(properties, "authToken", null));
        this.fromNumber = getString(properties, "from", null);

        log.debug("Twilio SMS provider configured: from={}", fromNumber);
    }

    @Override
    public void init() {
        if (accountSid == null || authToken == null) {
            throw new IllegalStateException("Twilio account-sid and auth-token are required");
        }

        Twilio.init(accountSid, authToken);
        initialized = true;

        log.info("Twilio SMS provider initialized");
    }

    @Override
    public void destroy() {
        // Twilio SDK doesn't require explicit cleanup
        log.debug("Twilio SMS provider destroyed");
    }

    @Override
    public SendResult send(NotificationRequest request, RenderedContent content) {
        if (!initialized) {
            return SendResult.failure("NOT_INITIALIZED", "Twilio provider not initialized");
        }

        SmsRecipient recipient = (SmsRecipient) request.getRecipient();

        try {
            Message message = Message.creator(
                    new PhoneNumber(recipient.getPhoneNumber()),
                    new PhoneNumber(fromNumber),
                    content.getTextBody()
            ).create();

            log.debug("SMS sent via Twilio: to={}, sid={}", recipient.getPhoneNumber(), message.getSid());

            return SendResult.success(message.getSid(), Map.of(
                    "status", message.getStatus().toString(),
                    "price", message.getPrice() != null ? message.getPrice() : "N/A"
            ));

        } catch (Exception e) {
            log.error("Failed to send SMS via Twilio: to={}, error={}",
                    recipient.getPhoneNumber(), e.getMessage());
            return SendResult.failure(e);
        }
    }

    @Override
    public boolean isHealthy() {
        return initialized;
    }

    private String getString(Map<String, Object> props, String key, String defaultValue) {
        Object value = props.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }
}
