package com.lazydevs.notification.channel.email.ses;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SesEmailProvider#withClient(SesV2Client)}: the send path against a mocked SES client.
 */
class SesEmailProviderSeamTest {

    private static final String RAW_TO = "john.doe@example.com";

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;
    private Level previousLevel;

    private SesV2Client client;
    private SesEmailProvider provider;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(SesEmailProvider.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);

        client = mock(SesV2Client.class);
        provider = SesEmailProvider.withClient(client);
        provider.configure(Map.of("from-address", "noreply@contoso.com", "from-name", "Contoso",
                "configuration-set", "transactional"));
        provider.init();
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
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, RAW_TO, List.of("cc@example.com"), List.of("bcc@example.com"),
                        "reply@example.com", "Fallback subject"))
                .build();
    }

    @Test
    void happyPath_sendsTheMappedRequest_andReturnsTheSesMessageId() {
        when(client.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("ses-msg-1").build());

        SendResult result = provider.send(request(), RenderedContent.email("Your results", "<p>Hi</p>", "Hi"));

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("ses-msg-1");
        ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(client).sendEmail(sent.capture());
        SendEmailRequest req = sent.getValue();
        assertThat(req.fromEmailAddress()).isEqualTo("Contoso <noreply@contoso.com>");
        assertThat(req.destination().toAddresses()).containsExactly(RAW_TO);
        assertThat(req.destination().ccAddresses()).containsExactly("cc@example.com");
        assertThat(req.destination().bccAddresses()).containsExactly("bcc@example.com");
        assertThat(req.replyToAddresses()).containsExactly("reply@example.com");
        assertThat(req.configurationSetName()).isEqualTo("transactional");
        assertThat(req.content().simple().subject().data()).isEqualTo("Your results");
        assertThat(req.content().simple().body().html().data()).isEqualTo("<p>Hi</p>");
        assertThat(req.content().simple().body().text().data()).isEqualTo("Hi");

        assertThat(logs()).contains("Email sent via SES", "j***@example.com", "ses-msg-1")
                .doesNotContain(RAW_TO, "Your results");
    }

    @Test
    void rejectedMessage_isPermanent_andTheErrorTextIsMasked() {
        when(client.sendEmail(any(SendEmailRequest.class))).thenThrow(MessageRejectedException.builder()
                .message("Email address is not verified: " + RAW_TO).build());

        SendResult result = provider.send(request(), RenderedContent.email("Your results", "<p>Hi</p>", "Hi"));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(result.errorMessage()).contains("j***@example.com").doesNotContain(RAW_TO);
        assertThat(logs()).contains("Failed to send email via SES", "j***@example.com").doesNotContain(RAW_TO);
    }

    @Test
    void injectedClient_isNotClosedByDestroy() {
        provider.destroy();

        verify(client, never()).close();
    }
}
