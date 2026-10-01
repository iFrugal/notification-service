package com.lazydevs.notification.channel.email.smtp;

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
import jakarta.mail.Message;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SmtpEmailProvider#withSender(SmtpSender)}: the send path with a capturing sender.
 */
class SmtpEmailProviderSeamTest {

    private static final String RAW_TO = "john.doe@example.com";

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(SmtpEmailProvider.class);
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

    private static SmtpEmailProvider provider(SmtpSender sender) {
        SmtpEmailProvider provider = SmtpEmailProvider.withSender(sender);
        // No host: the supplied sender owns the transport.
        provider.configure(Map.of("from-address", "noreply@contoso.com", "from-name", "Contoso"));
        provider.init();
        return provider;
    }

    private static NotificationRequest request() {
        return NotificationRequest.builder()
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, RAW_TO, List.of("cc@example.com"), null,
                        "reply@example.com", "Fallback subject"))
                .build();
    }

    private static List<String> addresses(jakarta.mail.Address[] addresses) {
        return addresses == null ? List.of()
                : Arrays.stream(addresses).map(a -> ((InternetAddress) a).getAddress()).toList();
    }

    @Test
    void happyPath_handsTheMappedMessageToTheSender() throws Exception {
        AtomicReference<MimeMessage> sent = new AtomicReference<>();
        SmtpEmailProvider provider = provider(sent::set);

        SendResult result = provider.send(request(), RenderedContent.email("Your results", "<p>Hi</p>", "Hi"));

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isNotBlank();
        MimeMessage message = sent.get();
        assertThat(message).isNotNull();
        assertThat(addresses(message.getFrom())).containsExactly("noreply@contoso.com");
        assertThat(addresses(message.getRecipients(Message.RecipientType.TO))).containsExactly(RAW_TO);
        assertThat(addresses(message.getRecipients(Message.RecipientType.CC))).containsExactly("cc@example.com");
        assertThat(addresses(message.getReplyTo())).containsExactly("reply@example.com");
        assertThat(message.getSubject()).isEqualTo("Your results");
        assertThat(((MimeMultipart) message.getContent()).getCount()).isEqualTo(2);
        assertThat(provider.isHealthy()).isTrue();

        assertThat(logs()).contains("Email sent via SMTP", "j***@example.com")
                .doesNotContain(RAW_TO, "Your results");
    }

    @Test
    void serverRejection_isTransient_andTheErrorTextIsMasked() {
        SmtpEmailProvider provider = provider(message -> {
            throw new SendFailedException("550 5.1.1 <" + RAW_TO + ">: Recipient address rejected");
        });

        SendResult result = provider.send(request(), RenderedContent.emailHtml("Your results", "<p>Hi</p>"));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(result.errorMessage()).isEqualTo("550 5.1.1 <j***@example.com>: Recipient address rejected");
        assertThat(logs()).contains("Failed to send email via SMTP", "j***@example.com").doesNotContain(RAW_TO);
    }
}
