package com.lazydevs.notification.channel.push.fcm.googleauth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.PushRecipient;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.channel.push.fcm.FcmHttpTransport;
import com.lazydevs.notification.channel.push.fcm.FcmPushProvider;
import com.lazydevs.notification.channel.push.fcm.FcmPushProviderAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.lazydevs.notification.channel.push.fcm.googleauth.GoogleStubServer.IMPERSONATION_PATH;
import static com.lazydevs.notification.channel.push.fcm.googleauth.GoogleStubServer.STS_PATH;
import static com.lazydevs.notification.channel.push.fcm.googleauth.GoogleStubServer.SUBJECT_TOKEN;
import static com.lazydevs.notification.channel.push.fcm.googleauth.GoogleStubServer.TOKEN_PATH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FcmPushProvider} with {@code credentials: adc} and {@code external-account:<path>},
 * found through {@code META-INF/services}: the token calls and the send all go through the
 * application's transport, and no secret reaches a log line, the Google libraries' own
 * {@code java.util.logging} output included.
 */
class GoogleAuthFcmEndToEndTest {

    private static final String DEVICE_TOKEN = "fGx1-raw-registration-token:APA91bHun4MxP5egoKMwt2KZFBaFUH-1RYqx";

    @TempDir
    Path dir;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger root;
    private Level previousLevel;
    private java.util.logging.Level previousGoogleLevel;
    private final java.util.logging.Logger google = java.util.logging.Logger.getLogger("com.google");

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        previousLevel = root.getLevel();
        root.setLevel(Level.TRACE);
        appender.start();
        root.addAppender(appender);
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
        previousGoogleLevel = google.getLevel();
        google.setLevel(java.util.logging.Level.ALL);
    }

    @AfterEach
    void restoreLogs() {
        google.setLevel(previousGoogleLevel);
        SLF4JBridgeHandler.uninstall();
        root.detachAppender(appender);
        root.setLevel(previousLevel);
    }

    private static NotificationRequest request() {
        return NotificationRequest.builder()
                .requestId("req-1")
                .tenantId("acme")
                .notificationType("ORDER_SHIPPED")
                .channel(Channel.PUSH)
                .recipient(new PushRecipient(null, DEVICE_TOKEN, null, null, "Title", "Body", null, null, null, null,
                        null))
                .build();
    }

    private static FcmPushProvider provider(FcmHttpTransport transport, Map<String, Object> properties) {
        FcmPushProvider provider = new FcmPushProvider(transport, List.of(),
                GoogleAuthFcmEndToEndTest.class.getClassLoader());
        provider.configure(properties);
        provider.init();
        return provider;
    }

    @Test
    void adc_sendsAPushWithTheTokenOfTheServiceAccountKey() {
        GoogleStubServer stub = AdcFixture.get().stub();
        StubRoutingTransport transport = new StubRoutingTransport(stub);
        FcmPushProvider provider = provider(transport, Map.of("credentials", "adc",
                "endpoint", stub.baseUri().toString()));

        SendResult result = provider.send(request(), null);

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        assertThat(result.messageId()).startsWith("projects/" + GoogleStubServer.SA_PROJECT + "/messages/");
        assertThat(transport.paths()).containsExactly(TOKEN_PATH,
                "/v1/projects/" + GoogleStubServer.SA_PROJECT + "/messages:send");
        assertThat(stub.sendAuthorizations().getLast()).startsWith("ya29.sa-");
        assertThat(provider.getHealthDetails()).containsEntry("credentials", "adc")
                .containsEntry("projectId", GoogleStubServer.SA_PROJECT);
        assertNoSecrets(stub, List.of(stub.sendAuthorizations().getLast()));
    }

    @Test
    void externalAccount_sendsAPushWithTheImpersonatedToken() throws IOException {
        try (GoogleStubServer stub = new GoogleStubServer()) {
            Path subjectToken = Files.writeString(dir.resolve("token"), SUBJECT_TOKEN);
            Path config = Files.writeString(dir.resolve("wif.json"),
                    GoogleStubServer.externalAccountJson("wif.invalid", subjectToken.toString(), true));
            StubRoutingTransport transport = new StubRoutingTransport(stub);
            FcmPushProvider provider = provider(transport, Map.of("credentials", "external-account:" + config,
                    "endpoint", stub.baseUri().toString()));

            SendResult result = provider.send(request(), null);

            assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
            assertThat(result.messageId()).startsWith("projects/wif-project/messages/");
            assertThat(transport.paths()).containsExactly(STS_PATH, IMPERSONATION_PATH,
                    "/v1/projects/wif-project/messages:send");
            assertThat(transport.count()).isEqualTo(stub.totalCalls());
            assertThat(CountingHttpTransportFactory.created()).isZero();
            assertNoSecrets(stub, List.of(SUBJECT_TOKEN, stub.sendAuthorizations().getLast(), "sts-"));
        }
    }

    @Test
    void autoConfiguredProvider_usesTheTransportBeanForTokenAndSend() {
        GoogleStubServer stub = AdcFixture.get().stub();
        StubRoutingTransport transport = new StubRoutingTransport(stub);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FcmPushProviderAutoConfiguration.class))
                .withBean(FcmHttpTransport.class, () -> transport)
                .run(context -> {
                    FcmPushProvider provider = context.getBean("fcmPushProvider", FcmPushProvider.class);
                    provider.configure(Map.of("credentials", "adc", "endpoint", stub.baseUri().toString()));
                    provider.init();

                    assertThat(provider.send(request(), null).success()).isTrue();
                    assertThat(transport.paths()).containsExactly(TOKEN_PATH,
                            "/v1/projects/" + GoogleStubServer.SA_PROJECT + "/messages:send");
                });
    }

    private void assertNoSecrets(GoogleStubServer stub, List<String> extra) {
        List<String> secrets = new ArrayList<>(List.of(DEVICE_TOKEN, "PRIVATE KEY",
                stub.privateKeyBase64().substring(0, 32), "ya29.",
                // Base64url of {"alg":"RS256": the start of every signed assertion.
                "eyJhbGciOiJSUzI1NiI"));
        secrets.addAll(extra);
        String logs = appender.list.stream().map(GoogleAuthFcmEndToEndTest::render).collect(Collectors.joining("\n"));
        assertThat(appender.list).as("the capture works").isNotEmpty();
        for (String secret : secrets) {
            assertThat(logs).doesNotContain(secret);
        }
    }

    private static String render(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
            text.append(" | ").append(t.getMessage());
        }
        return text.toString();
    }
}
