package com.lazydevs.notification.channel.email.acs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.azure.core.exception.HttpResponseException;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.channel.email.acs.AcsEmailProperties.SendMode;
import com.lazydevs.notification.channel.email.acs.AcsSendOutcome.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Recipient data never reaches the ACS provider's log lines or error text unmasked.
 */
class AcsEmailProviderLoggingTest {

    private static final String RAW_TO = "john.doe@example.com";
    private static final String MASKED_TO = "j***@example.com";
    private static final String SUBJECT = "Your lab results are ready";

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;
    private Level previousLevel;

    private AcsEmailGateway gateway;
    private AcsEmailProvider provider;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(AcsEmailProvider.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);

        gateway = mock(AcsEmailGateway.class);
        provider = new AcsEmailProvider(new AcsEmailProperties(null,
                "endpoint=https://x.communication.azure.com/;accesskey=a2V5", null, "DoNotReply@example.com",
                List.of(), SendMode.WAIT, Duration.ofSeconds(60), 0, null), gateway);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    private String logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    private static NotificationRequest request() {
        return NotificationRequest.builder()
                .notificationType("RESULTS")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, RAW_TO, List.of("cc.person@example.com"), null, null, SUBJECT))
                .build();
    }

    private static RenderedContent content() {
        return RenderedContent.email(SUBJECT, "<p>Dear John</p>", "Dear John");
    }

    @Test
    void successDebugLine_masksRecipient_andOmitsSubject() {
        when(gateway.send(any())).thenReturn(AcsSendOutcome.of("op-1", Status.SUCCEEDED));

        provider.send(request(), content());

        assertThat(logs()).contains("Email sent via ACS", MASKED_TO)
                .doesNotContain(RAW_TO, SUBJECT, "Dear John", "cc.person");
    }

    @Test
    void errorLine_andResultMessage_redactRecipientInExceptionText() {
        when(gateway.send(any())).thenThrow(
                new HttpResponseException("Invalid recipient " + RAW_TO + " or +15551234590", null));

        SendResult result = provider.send(request(), content());

        assertThat(logs()).contains("Failed to send email via ACS", MASKED_TO, "+1***90")
                .doesNotContain(RAW_TO, "+15551234590", SUBJECT);
        assertThat(result.errorMessage()).isEqualTo("Invalid recipient " + MASKED_TO + " or +1***90");
    }

    @Test
    void failedOutcome_redactsAcsErrorMessage() {
        when(gateway.send(any())).thenReturn(new AcsSendOutcome("op-9", Status.FAILED,
                "EmailDroppedAllRecipientsSuppressed", "Recipient " + RAW_TO + " is suppressed"));

        SendResult result = provider.send(request(), content());

        assertThat(logs()).contains("op-9", MASKED_TO).doesNotContain(RAW_TO);
        assertThat(result.errorMessage()).isEqualTo("Recipient " + MASKED_TO + " is suppressed");
    }

    @Test
    void configureDebugLine_masksSenderAndReplyTo() {
        AcsEmailProvider reflective = new AcsEmailProvider();

        reflective.configure(Map.of(
                "connection-string", "endpoint=https://x.communication.azure.com/;accesskey=a2V5",
                "sender", "DoNotReply@contoso.com",
                "reply-to", "support.team@contoso.com"));

        assertThat(logs()).contains("ACS email provider configured", "D***@contoso.com", "s***@contoso.com")
                .doesNotContain("DoNotReply@contoso.com", "support.team@contoso.com");
    }
}
