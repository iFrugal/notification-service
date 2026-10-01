package com.lazydevs.notification.channel.sms.twilio;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.model.SmsRecipient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recipient data never reaches the Twilio provider's log lines unmasked.
 */
class TwilioSmsProviderLoggingTest {

    private static final String RAW_PHONE = "+15551234590";
    private static final String MASKED_PHONE = "+1***90";

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(TwilioSmsProvider.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    private String logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    @Test
    void configureDebugLine_masksSenderNumber() {
        new TwilioSmsProvider().configure(Map.of("from", "+15557654321"));

        assertThat(logs()).contains("Twilio SMS provider configured", "+1***21")
                .doesNotContain("+15557654321");
    }

    @Test
    void errorLine_masksRecipientPhone() {
        TwilioSmsProvider provider = new TwilioSmsProvider();
        provider.configure(Map.of("account-sid", "ACtest", "auth-token", "token", "from", "+15557654321"));
        provider.init();
        NotificationRequest request = NotificationRequest.builder()
                .channel(Channel.SMS)
                .recipient(new SmsRecipient(null, RAW_PHONE))
                .build();

        // No content: the send fails inside the provider's try block, before any network call.
        SendResult result = provider.send(request, null);

        assertThat(result.success()).isFalse();
        assertThat(logs()).contains("Failed to send SMS via Twilio", MASKED_PHONE)
                .doesNotContain(RAW_PHONE);
    }
}
