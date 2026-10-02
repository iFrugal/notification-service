package com.lazydevs.notification.channel.push.fcm.googleauth;

import com.google.api.client.http.GenericUrl;
import com.lazydevs.notification.channel.push.fcm.FcmAccessToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.lazydevs.notification.channel.push.fcm.googleauth.GoogleStubServer.TOKEN_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Application Default Credentials through {@code GoogleCredentials.getApplicationDefault}:
 * a service-account key found through {@code GOOGLE_APPLICATION_CREDENTIALS} (see
 * {@link AdcFixture}), its token call through the tenant's transport.
 */
class ApplicationDefaultTokenProviderTest {

    private static final Duration MARGIN = Duration.ofMinutes(5);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private GoogleStubServer stub;

    @BeforeEach
    void setUp() {
        stub = AdcFixture.get().stub();
    }

    @Test
    void serviceAccountKey_signsTheGrantAndFetchesThroughTheTransport() {
        StubRoutingTransport transport = new StubRoutingTransport(stub);
        int tokenCallsBefore = stub.calls(TOKEN_PATH);

        GoogleAuthTokenProvider provider = GoogleAuthTokenProvider.applicationDefault(transport, Clock.systemUTC(),
                MARGIN, TIMEOUT);
        assertThat(transport.count()).as("resolving ADC from a key file makes no call").isZero();

        FcmAccessToken token = provider.token();

        assertThat(token.value()).startsWith("ya29.sa-");
        assertThat(token.expiresAt()).isCloseTo(Instant.now().plusSeconds(3600), within(30, ChronoUnit.SECONDS));
        assertThat(stub.problems()).as("JWT signature, iss, scope=firebase.messaging, aud").isEmpty();
        assertThat(stub.calls(TOKEN_PATH) - tokenCallsBefore).isEqualTo(1);
        assertThat(transport.paths()).containsExactly(TOKEN_PATH);
        assertThat(transport.requests().getFirst().uri().getHost()).isEqualTo(AdcFixture.TOKEN_HOST);
        assertThat(provider.projectId()).contains(GoogleStubServer.SA_PROJECT);
        assertThat(CountingHttpTransportFactory.created()).isZero();
    }

    @Test
    void twoTenants_eachFetchThroughTheirOwnTransport() {
        // The library caches ADC per JVM; the scoped factory still routes per tenant.
        StubRoutingTransport first = new StubRoutingTransport(stub);
        StubRoutingTransport second = new StubRoutingTransport(stub);
        GoogleAuthTokenProvider a = GoogleAuthTokenProvider.applicationDefault(first, Clock.systemUTC(), MARGIN, TIMEOUT);
        GoogleAuthTokenProvider b = GoogleAuthTokenProvider.applicationDefault(second, Clock.systemUTC(), MARGIN, TIMEOUT);

        a.token();
        b.token();
        b.invalidate();
        b.token();

        assertThat(first.count()).isEqualTo(1);
        assertThat(second.count()).isEqualTo(2);
    }

    @Test
    void invalidate_forcesARefresh() {
        StubRoutingTransport transport = new StubRoutingTransport(stub);
        GoogleAuthTokenProvider provider = GoogleAuthTokenProvider.applicationDefault(transport, Clock.systemUTC(),
                MARGIN, TIMEOUT);
        FcmAccessToken first = provider.token();
        assertThat(provider.token()).isEqualTo(first);

        provider.invalidate();

        assertThat(provider.token().value()).isNotEqualTo(first.value());
        assertThat(transport.count()).isEqualTo(2);
    }

    @Test
    void outsideATokenCall_theSharedAdcTransportRefusesRequests() {
        assertThatThrownBy(() -> ScopedTransportFactory.INSTANCE.create().createRequestFactory()
                .buildGetRequest(new GenericUrl("https://metadata.invalid/")).execute())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("refusing it");
    }
}
