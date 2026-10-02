package com.lazydevs.notification.channel.push.fcm.googleauth;

import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.channel.push.fcm.FcmAccessToken;
import com.lazydevs.notification.channel.push.fcm.FcmAuthenticationException;
import com.lazydevs.notification.channel.push.fcm.FcmHttpTransport;
import com.lazydevs.notification.channel.push.fcm.FcmTransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.lazydevs.notification.channel.push.fcm.googleauth.GoogleStubServer.CLOUD_PLATFORM_SCOPE;
import static com.lazydevs.notification.channel.push.fcm.googleauth.GoogleStubServer.FCM_SCOPE;
import static com.lazydevs.notification.channel.push.fcm.googleauth.GoogleStubServer.IMPERSONATION_PATH;
import static com.lazydevs.notification.channel.push.fcm.googleauth.GoogleStubServer.STS_PATH;
import static com.lazydevs.notification.channel.push.fcm.googleauth.GoogleStubServer.SUBJECT_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Workload identity federation through {@code ExternalAccountCredentials}: a file-sourced
 * subject token exchanged at STS, with and without service account impersonation, every
 * call through the application's transport.
 */
class ExternalAccountTokenProviderTest {

    private static final String HOST = "wif.invalid";
    private static final Duration MARGIN = Duration.ofMinutes(5);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path dir;

    private final GoogleStubServer stub = new GoogleStubServer();
    private final StubRoutingTransport transport = new StubRoutingTransport(stub);

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private Path config(boolean impersonate) throws IOException {
        Path subjectToken = Files.writeString(dir.resolve("subject-token"), SUBJECT_TOKEN);
        return Files.writeString(dir.resolve("wif.json"),
                GoogleStubServer.externalAccountJson(HOST, subjectToken.toString(), impersonate));
    }

    private GoogleAuthTokenProvider provider(boolean impersonate, Clock clock) throws IOException {
        return GoogleAuthTokenProvider.externalAccount(config(impersonate), transport, clock, MARGIN, TIMEOUT);
    }

    @Test
    void withImpersonation_exchangesAtStsThenImpersonates_allThroughTheTransport() throws IOException {
        Instant expiry = Instant.now().plus(50, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        stub.impersonationExpiry(expiry);
        int fallbackBefore = CountingHttpTransportFactory.created();

        GoogleAuthTokenProvider provider = provider(true, Clock.systemUTC());
        assertThat(transport.count()).as("create() makes no call").isZero();

        FcmAccessToken token = provider.token();

        assertThat(token.value()).startsWith("ya29.impersonated-");
        assertThat(token.expiresAt()).as("expireTime of the IAM answer").isEqualTo(expiry);
        assertThat(stub.problems()).isEmpty();
        assertThat(stub.calls(STS_PATH)).isEqualTo(1);
        assertThat(stub.calls(IMPERSONATION_PATH)).isEqualTo(1);
        assertThat(stub.stsScopes()).containsExactly(CLOUD_PLATFORM_SCOPE);
        assertThat(transport.paths()).containsExactly(STS_PATH, IMPERSONATION_PATH);
        assertThat(transport.count()).as("every stub call came through the transport").isEqualTo(stub.totalCalls());
        assertThat(CountingHttpTransportFactory.created()).isEqualTo(fallbackBefore).isZero();
        assertThat(provider.projectId()).contains("wif-project");
    }

    @Test
    void withoutImpersonation_usesTheStsTokenScopedToFcm() throws IOException {
        stub.expiresIn(1800);
        GoogleAuthTokenProvider provider = provider(false, Clock.systemUTC());

        FcmAccessToken token = provider.token();

        assertThat(token.value()).startsWith("sts-");
        assertThat(token.expiresAt()).isCloseTo(Instant.now().plusSeconds(1800), within(30, ChronoUnit.SECONDS));
        assertThat(stub.stsScopes()).containsExactly(FCM_SCOPE);
        assertThat(stub.calls(IMPERSONATION_PATH)).isZero();
        assertThat(transport.paths()).containsExactly(STS_PATH);
        assertThat(CountingHttpTransportFactory.created()).isZero();
    }

    @Test
    void cachedToken_servesLaterCallsWithoutHttp() throws IOException {
        GoogleAuthTokenProvider provider = provider(false, Clock.systemUTC());

        FcmAccessToken first = provider.token();
        FcmAccessToken second = provider.token();

        assertThat(second).isEqualTo(first);
        assertThat(transport.count()).isEqualTo(1);
    }

    @Test
    void invalidate_forcesARefresh() throws IOException {
        GoogleAuthTokenProvider provider = provider(true, Clock.systemUTC());
        FcmAccessToken first = provider.token();

        provider.invalidate();
        FcmAccessToken second = provider.token();

        assertThat(second.value()).isNotEqualTo(first.value()).startsWith("ya29.impersonated-");
        assertThat(stub.calls(IMPERSONATION_PATH)).isEqualTo(2);
    }

    @Test
    void pastTheRefreshMargin_refreshes() throws IOException {
        MutableClock clock = new MutableClock();
        stub.expiresIn(3600);
        GoogleAuthTokenProvider provider = provider(false, clock);
        FcmAccessToken first = provider.token();

        clock.advance(Duration.ofMinutes(54));
        assertThat(provider.token()).as("still outside the margin").isEqualTo(first);
        clock.advance(Duration.ofMinutes(2));
        FcmAccessToken refreshed = provider.token();

        assertThat(refreshed.value()).isNotEqualTo(first.value());
        assertThat(stub.calls(STS_PATH)).isEqualTo(2);
    }

    @Test
    void concurrentCallers_shareOneRefresh() throws Exception {
        GoogleAuthTokenProvider provider = provider(false, Clock.systemUTC());
        List<Thread> threads = new java.util.ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    provider.token();
                } catch (RuntimeException e) {
                    failures.incrementAndGet();
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(failures).hasValue(0);
        assertThat(stub.calls(STS_PATH)).isEqualTo(1);
    }

    @Test
    void stsRejectsTheSubjectToken_isPermanent() throws IOException {
        stub.enqueue(STS_PATH, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"token expired\"}");
        GoogleAuthTokenProvider provider = provider(false, Clock.systemUTC());

        assertThatThrownBy(provider::token)
                .isInstanceOfSatisfying(FcmAuthenticationException.class,
                        e -> assertThat(e.failureType()).isEqualTo(FailureType.PERMANENT))
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("invalid_grant")
                .hasMessageNotContaining(SUBJECT_TOKEN);
    }

    @Test
    void stsUnavailable_isTransient() throws IOException {
        stub.enqueue(STS_PATH, 503, "{\"error\":\"temporarily_unavailable\"}");
        GoogleAuthTokenProvider provider = provider(false, Clock.systemUTC());

        assertThatThrownBy(provider::token)
                .isInstanceOfSatisfying(FcmAuthenticationException.class,
                        e -> assertThat(e.failureType()).isEqualTo(FailureType.TRANSIENT))
                .hasMessageContaining("HTTP 503");
    }

    @Test
    void impersonationDenied_isPermanent() throws IOException {
        stub.enqueue(IMPERSONATION_PATH, 403,
                "{\"error\":{\"code\":403,\"message\":\"Permission 'iam.serviceAccounts.getAccessToken' denied\","
                        + "\"status\":\"PERMISSION_DENIED\"}}");
        GoogleAuthTokenProvider provider = provider(true, Clock.systemUTC());

        assertThatThrownBy(provider::token)
                .isInstanceOfSatisfying(FcmAuthenticationException.class,
                        e -> assertThat(e.failureType()).isEqualTo(FailureType.PERMANENT))
                .hasMessageContaining("HTTP 403");
    }

    @Test
    void noAnswerFromTheTokenEndpoint_isTransient() throws IOException {
        FcmHttpTransport refused = request -> {
            throw FcmTransportException.notSent("ConnectException calling " + request.uri(), null);
        };
        GoogleAuthTokenProvider provider = GoogleAuthTokenProvider.externalAccount(config(false), refused,
                Clock.systemUTC(), MARGIN, TIMEOUT);

        assertThatThrownBy(provider::token)
                .isInstanceOfSatisfying(FcmAuthenticationException.class,
                        e -> assertThat(e.failureType()).isEqualTo(FailureType.TRANSIENT))
                .hasMessageContaining("did not answer")
                .hasMessageContaining("ConnectException");
    }

    @Test
    void missingSubjectTokenFile_isUnknownAndNamesTheProblem() throws IOException {
        Path config = Files.writeString(dir.resolve("wif.json"),
                GoogleStubServer.externalAccountJson(HOST, dir.resolve("absent").toString(), false));
        GoogleAuthTokenProvider provider = GoogleAuthTokenProvider.externalAccount(config, transport,
                Clock.systemUTC(), MARGIN, TIMEOUT);

        assertThatThrownBy(provider::token)
                .isInstanceOfSatisfying(FcmAuthenticationException.class,
                        e -> assertThat(e.failureType()).isEqualTo(FailureType.UNKNOWN))
                .hasMessageContaining("external-account:");
        assertThat(transport.count()).isZero();
    }

    @Test
    void unreadableConfig_failsConfigurationNamingThePath() {
        Path absent = dir.resolve("absent.json");

        assertThatThrownBy(() -> GoogleAuthTokenProvider.externalAccount(absent, transport, Clock.systemUTC(),
                MARGIN, TIMEOUT))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("cannot read the external account configuration '" + absent + "'")
                .hasMessageContaining("NoSuchFileException");
    }

    @Test
    void serviceAccountKeyGivenAsExternalAccount_failsConfiguration() throws IOException {
        Path key = Files.writeString(dir.resolve("key.json"), stub.serviceAccountJson("https://oauth2.googleapis.com/token"));

        assertThatThrownBy(() -> GoogleAuthTokenProvider.externalAccount(key, transport, Clock.systemUTC(),
                MARGIN, TIMEOUT))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("is not usable")
                .hasMessageContaining("service_account")
                .hasMessageContaining("external_account")
                .hasMessageNotContaining(stub.privateKeyBase64().substring(0, 32));
    }

    @Test
    void plainHttpTokenUrl_isRejectedByTheLibrary() throws IOException {
        String json = GoogleStubServer.externalAccountJson(HOST, "/tmp/token", false)
                .replace("https://" + HOST, "http://" + HOST);
        Path config = Files.writeString(dir.resolve("http.json"), json);

        assertThatThrownBy(() -> GoogleAuthTokenProvider.externalAccount(config, transport, Clock.systemUTC(),
                MARGIN, TIMEOUT))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("token URL is invalid");
    }
}
