package com.lazydevs.notification.channel.email.ses;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recipient data never reaches the SES provider's log lines unmasked.
 */
class SesEmailProviderLoggingTest {

    private static final String RAW_TO = "john.doe@example.com";
    private static final String MASKED_TO = "j***@example.com";

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(SesEmailProvider.class);
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
    void configureDebugLine_masksSender() {
        new SesEmailProvider().configure(Map.of("region", "eu-west-1", "from-address", "Sender.Name@contoso.com"));

        assertThat(logs()).contains("SES provider configured", "S***@contoso.com")
                .doesNotContain("Sender.Name@contoso.com");
    }

    @Test
    void errorLine_masksRecipient() {
        SesEmailProvider provider = new SesEmailProvider();
        provider.configure(Map.of("from-address", "sender@contoso.com"));
        NotificationRequest request = NotificationRequest.builder()
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, RAW_TO, null, null, null, "Private subject"))
                .build();

        // Not initialised: the send fails inside the provider's try block.
        SendResult result = provider.send(request, null);

        assertThat(result.success()).isFalse();
        assertThat(logs()).contains("Failed to send email via SES", MASKED_TO)
                .doesNotContain(RAW_TO, "Private subject");
    }
}
