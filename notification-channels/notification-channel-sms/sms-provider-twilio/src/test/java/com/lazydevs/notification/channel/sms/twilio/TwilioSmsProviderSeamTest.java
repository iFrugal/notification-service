package com.lazydevs.notification.channel.sms.twilio;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.model.SmsRecipient;
import com.twilio.http.Request;
import com.twilio.http.Response;
import com.twilio.http.TwilioRestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TwilioSmsProvider#withClient(TwilioRestClient)}: the send path against a mocked
 * Twilio client, without {@code Twilio.init}.
 */
class TwilioSmsProviderSeamTest {

    private static final String RAW_TO = "+15551234590";

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;
    private Level previousLevel;

    private TwilioRestClient client;
    private TwilioSmsProvider provider;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(TwilioSmsProvider.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);

        client = mock(TwilioRestClient.class);
        when(client.getAccountSid()).thenReturn("ACtest");
        when(client.getObjectMapper()).thenReturn(new ObjectMapper());
        provider = TwilioSmsProvider.withClient(client);
        // No account-sid or auth-token: the supplied client carries the account.
        provider.configure(Map.of("from", "+15557654321"));
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
                .channel(Channel.SMS)
                .recipient(new SmsRecipient(null, RAW_TO))
                .build();
    }

    @Test
    void happyPath_postsTheMessageThroughTheSuppliedClient() {
        when(client.request(any(Request.class))).thenReturn(new Response(
                "{\"sid\":\"SM0123456789abcdef\",\"status\":\"queued\",\"to\":\"" + RAW_TO + "\"}", 201));

        SendResult result = provider.send(request(), RenderedContent.text("Your code is 4711"));

        assertThat(provider.isHealthy()).isTrue();
        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("SM0123456789abcdef");
        assertThat(result.providerMetadata()).containsEntry("status", "queued").containsEntry("price", "N/A");
        ArgumentCaptor<Request> sent = ArgumentCaptor.forClass(Request.class);
        verify(client).request(sent.capture());
        assertThat(sent.getValue().getUrl()).endsWith("/2010-04-01/Accounts/ACtest/Messages.json");
        assertThat(sent.getValue().getPostParams())
                .containsEntry("To", List.of(RAW_TO))
                .containsEntry("From", List.of("+15557654321"))
                .containsEntry("Body", List.of("Your code is 4711"));

        assertThat(logs()).contains("SMS sent via Twilio", "+1***90", "SM0123456789abcdef")
                .doesNotContain(RAW_TO, "4711");
    }

    @Test
    void invalidNumber_isPermanent_andTheErrorTextIsMasked() {
        when(client.request(any(Request.class))).thenReturn(new Response(
                "{\"code\":21211,\"message\":\"The 'To' number " + RAW_TO + " is not a valid phone number.\","
                        + "\"more_info\":\"https://www.twilio.com/docs/errors/21211\",\"status\":400}", 400));

        SendResult result = provider.send(request(), RenderedContent.text("Your code is 4711"));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(result.errorMessage()).isEqualTo("The 'To' number +1***90 is not a valid phone number.");
        assertThat(logs()).contains("Failed to send SMS via Twilio", "+1***90").doesNotContain(RAW_TO);
    }
}
