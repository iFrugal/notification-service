package com.lazydevs.notification.channel.whatsapp.meta;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.model.SmsRecipient;
import com.lazydevs.notification.api.model.WhatsAppRecipient;
import com.lazydevs.notification.channel.whatsapp.meta.MetaStubServer.StubResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;

import static com.lazydevs.notification.channel.whatsapp.meta.MetaTestSupport.content;
import static com.lazydevs.notification.channel.whatsapp.meta.MetaTestSupport.provider;
import static com.lazydevs.notification.channel.whatsapp.meta.MetaTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MetaWhatsAppProvider} against {@link MetaStubServer}: the request it
 * sends and the result of every kind of answer.
 */
class MetaWhatsAppProviderTest {

    private final MetaStubServer stub = new MetaStubServer();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(MetaWhatsAppProvider.class);

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        stub.close();
    }

    @Test
    void success_postsTheTemplateWithBearerToken_andReturnsTheWamid() {
        MetaWhatsAppProvider provider = provider(stub, Map.of());

        SendResult result = provider.send(request(), content());

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("wamid.STUB1");
        MetaStubServer.SendRequest sent = stub.requests().getFirst();
        assertThat(sent.path()).isEqualTo("/v25.0/" + MetaStubServer.PHONE_NUMBER_ID + "/messages");
        assertThat(sent.authorization()).isEqualTo("Bearer " + MetaStubServer.ACCESS_TOKEN);
        assertThat(sent.contentType()).isEqualTo("application/json");
        assertThat(sent.body().path("to").asText()).isEqualTo("15550100001");
        assertThat(sent.body().path("template").path("name").asText()).isEqualTo("order_shipped");
        assertThat(sent.body().path("biz_opaque_callback_data").asText()).isEqualTo("req-1");
        assertThat(provider.isHealthy()).isTrue();
    }

    @Test
    void http429_isThrottled_withRetryAfter() {
        stub.enqueue(StubResponse.of(429, MetaStubServer.graphError(130429, "Rate limit hit"))
                .withHeader("Retry-After", "17"));

        SendResult result = provider(stub, Map.of()).send(request(), content());

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(result.errorCode()).isEqualTo(MetaErrorCodes.THROTTLED);
        assertThat(result.retryAfter()).contains(Duration.ofSeconds(17));
    }

    @Test
    void pairRateLimit_usesTheConfiguredFloor() {
        stub.enqueue(StubResponse.of(400, MetaStubServer.graphError(131056, "Pair rate limit hit")));

        SendResult result = provider(stub, Map.of("pair-rate-limit-retry-after", "9s")).send(request(), content());

        assertThat(result.errorCode()).isEqualTo(MetaErrorCodes.PAIR_RATE_LIMITED);
        assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(result.retryAfter()).contains(Duration.ofSeconds(9));
    }

    @Test
    void serverError_isProviderUnavailable() {
        stub.enqueue(StubResponse.of(503, "upstream down"));

        SendResult result = provider(stub, Map.of()).send(request(), content());

        assertThat(result.errorCode()).isEqualTo(MetaErrorCodes.PROVIDER_UNAVAILABLE);
        assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void templateDoesNotExist_isPermanentTemplateRejected() {
        stub.enqueue(StubResponse.of(404, MetaStubServer.graphError(132001, "Template name does not exist")));

        SendResult result = provider(stub, Map.of()).send(request(), content());

        assertThat(result.errorCode()).isEqualTo(MetaErrorCodes.TEMPLATE_REJECTED);
        assertThat(result.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(result.errorMessage()).contains("132001");
    }

    @Test
    void rejectedToken_turnsHealthDown_untilASendSucceeds() {
        MetaWhatsAppProvider provider = provider(stub, Map.of("access-token", "expired-token"));

        SendResult rejected = provider.send(request(), content());

        assertThat(rejected.errorCode()).isEqualTo(MetaErrorCodes.CREDENTIAL_REJECTED);
        assertThat(rejected.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(provider.isHealthy()).isFalse();
        assertThat(provider.getHealthDetails()).containsEntry("status", "DOWN")
                .containsEntry("lastError", MetaErrorCodes.CREDENTIAL_REJECTED);
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(line -> line.contains("expired-token"));

        provider.configure(stub.properties());
        provider.init();
        assertThat(provider.isHealthy()).isTrue();
    }

    @Test
    void restrictedAccount_turnsHealthDown_andASuccessBringsItBack() {
        MetaWhatsAppProvider provider = provider(stub, Map.of());
        stub.enqueue(StubResponse.of(400, MetaStubServer.graphError(131031, "Business account locked")));

        assertThat(provider.send(request(), content()).errorCode()).isEqualTo(MetaErrorCodes.ACCOUNT_RESTRICTED);
        assertThat(provider.isHealthy()).isFalse();

        assertThat(provider.send(request(), content()).success()).isTrue();
        assertThat(provider.isHealthy()).isTrue();
        assertThat(provider.getHealthDetails()).containsEntry("status", "UP").doesNotContainKey("lastError");
    }

    @Test
    void readTimeout_isAmbiguousNoConfirmation() {
        stub.enqueue(StubResponse.of(200, MetaStubServer.accepted("wamid.LATE")).after(Duration.ofSeconds(3)));

        SendResult result = provider(stub, Map.of("timeout", "300ms")).send(request(), content());

        assertThat(result.failureType()).isEqualTo(FailureType.AMBIGUOUS);
        assertThat(result.errorCode()).isEqualTo(MetaErrorCodes.NO_CONFIRMATION);
    }

    @Test
    void successWithoutMessageId_isAmbiguous() {
        stub.enqueue(StubResponse.of(200, "{\"messaging_product\":\"whatsapp\"}"));

        SendResult result = provider(stub, Map.of()).send(request(), content());

        assertThat(result.failureType()).isEqualTo(FailureType.AMBIGUOUS);
        assertThat(result.errorCode()).isEqualTo(MetaErrorCodes.NO_CONFIRMATION);
    }

    @Test
    void connectionRefused_isTransientConnectFailed() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        MetaWhatsAppProvider provider = new MetaWhatsAppProvider();
        provider.configure(Map.of("base-url", "http://127.0.0.1:" + closedPort,
                "access-token", "t", "phone-number-id", "1"));
        provider.init();

        SendResult result = provider.send(request(), content());

        assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(result.errorCode()).isEqualTo(MetaErrorCodes.CONNECT_FAILED);
    }

    @Test
    void noTemplatePayload_orNoWhatsAppRecipient_failBeforeSending() {
        MetaWhatsAppProvider provider = provider(stub, Map.of());
        NotificationRequest sms = request();
        sms.setRecipient(new SmsRecipient(null, MetaTestSupport.PHONE));
        NotificationRequest badNumber = request();
        badNumber.setRecipient(new WhatsAppRecipient(null, "not-a-number", null, null, null, null, null));

        assertThat(provider.send(request(), RenderedContent.text("free text")).errorCode())
                .isEqualTo(MetaErrorCodes.TEMPLATE_REQUIRED);
        assertThat(provider.send(request(), null).errorCode()).isEqualTo(MetaErrorCodes.TEMPLATE_REQUIRED);
        assertThat(provider.send(sms, content()).errorCode()).isEqualTo(MetaErrorCodes.INVALID_RECIPIENT);
        assertThat(provider.send(badNumber, content()).failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void credentialsProvider_winsOverProperties_andAFailureIsTransient() {
        MetaWhatsAppProvider provider = new MetaWhatsAppProvider(null,
                tenant -> new MetaCredentials(MetaStubServer.ACCESS_TOKEN, "100000000000009"));
        provider.configure(stub.properties());
        provider.init();

        assertThat(provider.send(request(), content()).success()).isTrue();
        assertThat(stub.requests().getFirst().path()).isEqualTo("/v25.0/100000000000009/messages");
        assertThat(provider.getHealthDetails().get("credentials")).isNotEqualTo("properties");

        MetaWhatsAppProvider failing = new MetaWhatsAppProvider(null, tenant -> {
            throw new IllegalStateException("vault down");
        });
        failing.configure(Map.of("base-url", stub.baseUri().toString()));
        failing.init();
        SendResult result = failing.send(request(), content());
        assertThat(result.errorCode()).isEqualTo(MetaErrorCodes.CREDENTIALS_UNAVAILABLE);
        assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void lifecycle_isEnforced() {
        MetaWhatsAppProvider provider = new MetaWhatsAppProvider();
        Map<String, Object> noCredentials = Map.of();

        assertThatThrownBy(provider::init).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provider.send(request(), content())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provider.configure(noCredentials))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("MetaCredentialsProvider");
        assertThat(provider.getProviderName()).isEqualTo("meta");
        assertThat(provider.isHealthy()).isFalse();
        assertThat(provider.getHealthDetails()).containsEntry("status", "DOWN");

        MetaWhatsAppProvider ready = provider(stub, Map.of());
        assertThat(ready.transport()).isSameAs(JdkMetaHttpTransport.shared());
        assertThat(ready.settings().apiVersion()).isEqualTo("v25.0");
        ready.destroy();
        assertThat(ready.isHealthy()).isFalse();
    }
}
