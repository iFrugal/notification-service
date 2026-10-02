package com.lazydevs.notification.channel.push.fcm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lazydevs.notification.api.model.PushRecipient;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.channel.push.fcm.FcmStubServer.StubResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.lazydevs.notification.channel.push.fcm.FcmStubServer.fcmError;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.FID;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.TOKEN;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.TOKEN_2;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.configured;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * No private key, assertion, access token, device token or installation id
 * reaches a log line (at DEBUG) or a {@code SendResult.errorMessage}.
 */
class FcmPushProviderLoggingTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final FcmStubServer stub = new FcmStubServer();
    private final List<SendResult> results = new ArrayList<>();
    private Logger logger;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger("com.lazydevs.notification.channel.push.fcm");
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        stub.close();
    }

    private String logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    private String errorMessages() {
        return results.stream().map(SendResult::errorMessage).filter(m -> m != null)
                .collect(Collectors.joining("\n"));
    }

    private void send(FcmPushProvider provider, PushRecipient recipient) {
        results.add(provider.send(request(recipient), null));
    }

    private List<String> secrets() {
        return List.of(TOKEN, TOKEN_2, FID,
                stub.privateKeyBase64().substring(0, 32),
                "PRIVATE KEY",
                "stub-access-token-",
                // Base64url of {"alg":"RS256": the start of every assertion.
                "eyJhbGciOiJSUzI1NiI");
    }

    @Test
    void everyPath_keepsSecretsAndRawTargetsOut() {
        FcmPushProvider provider = configured(stub, Map.of("timeout", "500ms"));

        send(provider, FcmTestSupport.token(TOKEN));
        send(provider, FcmTestSupport.tokens(TOKEN, TOKEN_2));
        send(provider, FcmTestSupport.fid(FID));

        stub.respondWith(request -> StubResponse.of(404, fcmError(404, "NOT_FOUND", "UNREGISTERED",
                "The token " + request.target() + " is not registered")));
        send(provider, FcmTestSupport.token(TOKEN));
        send(provider, FcmTestSupport.tokens(TOKEN, TOKEN_2));
        send(provider, FcmTestSupport.fid(FID));

        stub.respondWith(request -> StubResponse.of(200, FcmStubServer.success("projects/p/messages/1"))
                .after(Duration.ofSeconds(2)));
        send(provider, FcmTestSupport.token(TOKEN));

        stub.respondWith(request -> null);
        stub.revokeTokens();
        stub.enqueueToken(StubResponse.of(400, "{\"error\":\"invalid_grant\"}"));
        send(provider, FcmTestSupport.token(TOKEN));

        send(provider, new PushRecipient(null, TOKEN, "news", null, "T", "B", null, null, null, null, null));

        assertThat(logs()).contains("FCM push provider configured", "FCM push provider initialized",
                "Push sent via FCM", "FCM send failed", "FCM multi-token send", FcmJson.targetHash(TOKEN),
                "message rejected before sending");
        assertThat(logs()).doesNotContain(secrets().toArray(String[]::new))
                .as("not even the masked suffix of a token").doesNotContain(TOKEN.substring(TOKEN.length() - 4));
        // FCM's message quoted the token; the provider replaced it with the hash.
        assertThat(errorMessages()).contains("The token " + FcmJson.targetHash(TOKEN) + " is not registered");
        assertThat(errorMessages()).doesNotContain(secrets().toArray(String[]::new));
        assertThat(results).allSatisfy(r -> assertThat(String.valueOf(r.providerMetadata()))
                .doesNotContain(secrets().toArray(String[]::new)));
    }

    @Test
    void logTokenHashFalse_hidesEvenTheHash() {
        FcmPushProvider provider = configured(stub, Map.of("log-token-hash", false));
        stub.enqueueSend(StubResponse.of(404, fcmError(404, "NOT_FOUND", "UNREGISTERED", "gone")));

        send(provider, FcmTestSupport.token(TOKEN));
        send(provider, FcmTestSupport.token(TOKEN));

        assertThat(logs()).contains("token=(hidden)").doesNotContain(FcmJson.targetHash(TOKEN), TOKEN);
    }

    @Test
    void settingsToString_redactsInlineCredentials() {
        FcmSettings settings = FcmSettings.fromMap(stub.properties());

        assertThat(settings.toString()).contains("credentials=service-account-json (inline)")
                .doesNotContain(secrets().toArray(String[]::new));
    }
}
