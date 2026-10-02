package com.lazydevs.notification.channel.push.fcm;

import com.fasterxml.jackson.databind.JsonNode;
import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.channel.push.fcm.FcmStubServer.StubResponse;
import com.lazydevs.notification.channel.push.fcm.FcmTestSupport.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.TOKEN;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.configured;
import static com.lazydevs.notification.channel.push.fcm.FcmTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The service-account JWT bearer flow against the stub token endpoint, which
 * verifies the RS256 signature, {@code iss}, {@code scope}, {@code aud} and {@code exp}.
 */
class ServiceAccountJwtTokenProviderTest {

    private final MutableClock clock = new MutableClock(Instant.now());
    private final FcmStubServer stub = new FcmStubServer(clock);

    @AfterEach
    void tearDown() {
        assertThat(stub.assertionProblems()).as("assertions the stub rejected").isEmpty();
        stub.close();
    }

    private ServiceAccountJwtTokenProvider tokenProvider(String json, URI tokenEndpoint) {
        return ServiceAccountJwtTokenProvider.fromCredentials(json, JdkFcmHttpTransport.shared(), clock,
                tokenEndpoint, Duration.ofMinutes(5), Duration.ofSeconds(5));
    }

    @Test
    void oneTokenCall_servesManySends() {
        FcmPushProvider provider = configured(stub, Map.of(), clock);

        for (int i = 0; i < 5; i++) {
            assertThat(provider.send(request(FcmTestSupport.token(TOKEN)), null).success()).isTrue();
        }

        assertThat(stub.tokenCalls()).isEqualTo(1);
        assertThat(stub.sendRequests()).extracting(FcmStubServer.SendRequest::authorization)
                .containsOnly("Bearer stub-access-token-1");
    }

    @Test
    void clockPastTheRefreshMargin_fetchesANewToken() {
        FcmPushProvider provider = configured(stub, Map.of("token-refresh-margin", "5m"), clock);
        provider.send(request(FcmTestSupport.token(TOKEN)), null);

        clock.advance(Duration.ofMinutes(54));
        provider.send(request(FcmTestSupport.token(TOKEN)), null);
        assertThat(stub.tokenCalls()).as("54 minutes in, before expiry minus 5 minutes").isEqualTo(1);

        clock.advance(Duration.ofMinutes(2));
        provider.send(request(FcmTestSupport.token(TOKEN)), null);
        assertThat(stub.tokenCalls()).as("56 minutes in, inside the margin").isEqualTo(2);
        assertThat(stub.sendRequests().getLast().authorization()).isEqualTo("Bearer stub-access-token-2");
    }

    @Test
    void twentyConcurrentSenders_causeOneRefresh() throws Exception {
        FcmPushProvider provider = configured(stub, Map.of("concurrency", 20), clock);
        stub.tokenDelay(Duration.ofMillis(300));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SendResult>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            for (int i = 0; i < 20; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return provider.send(request(FcmTestSupport.token(TOKEN)), null);
                }));
            }
            start.countDown();
            for (Future<SendResult> result : results) {
                assertThat(result.get().success()).isTrue();
            }
        }

        assertThat(stub.tokenCalls()).isEqualTo(1);
        assertThat(stub.sendRequests()).hasSize(20);
    }

    @Test
    void assertion_hasTheDocumentedHeaderAndClaims() throws Exception {
        ServiceAccountJwtTokenProvider provider = tokenProvider(stub.serviceAccountJson(), null);
        Instant now = Instant.parse("2026-10-02T10:00:00Z");

        String[] parts = provider.assertion(now).split("\\.");

        JsonNode header = FcmJson.MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = FcmJson.MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(FcmJson.writeString(header)).isEqualTo(
                "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + FcmStubServer.PRIVATE_KEY_ID + "\"}");
        assertThat(FcmJson.writeString(claims)).isEqualTo("{\"iss\":\"" + FcmStubServer.CLIENT_EMAIL + "\","
                + "\"scope\":\"https://www.googleapis.com/auth/firebase.messaging\","
                + "\"aud\":\"https://oauth2.googleapis.com/token\","
                + "\"iat\":" + now.getEpochSecond() + ",\"exp\":" + (now.getEpochSecond() + 3600) + "}");
        assertThat(parts[2]).isNotBlank();
        assertThat(provider.projectId()).contains(FcmStubServer.PROJECT_ID);
    }

    @Test
    void tokenUriOfTheKey_isIgnoredUnlessItIsGoogles_orTokenEndpointIsSet() {
        assertThat(tokenProvider(stub.serviceAccountJson("https://attacker.example/token"), null).tokenUri())
                .isEqualTo(ServiceAccountJwtTokenProvider.GOOGLE_TOKEN_URI);
        assertThat(tokenProvider(stub.serviceAccountJson("https://oauth2.googleapis.com/token"), null).tokenUri())
                .isEqualTo(ServiceAccountJwtTokenProvider.GOOGLE_TOKEN_URI);
        assertThat(tokenProvider(stub.serviceAccountJson("https://attacker.example/token"), stub.tokenUri())
                .tokenUri()).isEqualTo(stub.tokenUri());
    }

    @Test
    void invalidGrant_isPermanent() {
        FcmPushProvider provider = configured(stub, Map.of(), clock);
        stub.enqueueToken(StubResponse.of(400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}"));

        SendResult result = provider.send(request(FcmTestSupport.token(TOKEN)), null);

        assertThat(result.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(result.errorCode()).isEqualTo("FCM_AUTH_FAILED");
        assertThat(result.errorMessage()).contains("invalid_grant", "Invalid JWT Signature.");
        assertThat(stub.sendRequests()).isEmpty();
    }

    @Test
    void invalidClient_isPermanent() {
        FcmPushProvider provider = configured(stub, Map.of(), clock);
        stub.enqueueToken(StubResponse.of(401, "{\"error\":\"invalid_client\"}"));

        assertThat(provider.send(request(FcmTestSupport.token(TOKEN)), null).failureType())
                .isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void tokenEndpoint5xx_isTransient_andTheNextSendTriesAgain() {
        FcmPushProvider provider = configured(stub, Map.of(), clock);
        stub.enqueueToken(StubResponse.of(503, "{\"error\":\"temporarily_unavailable\"}"));

        SendResult first = provider.send(request(FcmTestSupport.token(TOKEN)), null);
        SendResult second = provider.send(request(FcmTestSupport.token(TOKEN)), null);

        assertThat(first.failureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(first.errorCode()).isEqualTo("FCM_AUTH_FAILED");
        assertThat(second.success()).isTrue();
        assertThat(stub.tokenCalls()).isEqualTo(2);
    }

    @Test
    void unreachableTokenEndpoint_isTransient() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        ServiceAccountJwtTokenProvider provider = tokenProvider(stub.serviceAccountJson(),
                URI.create("http://127.0.0.1:" + closedPort + "/token"));

        assertThatThrownBy(provider::token)
                .isInstanceOfSatisfying(FcmAuthenticationException.class,
                        e -> assertThat(e.failureType()).isEqualTo(FailureType.TRANSIENT));
    }

    @Test
    void successWithoutAccessToken_isUnknown() {
        stub.enqueueToken(StubResponse.of(200, "{\"token_type\":\"Bearer\"}"));
        ServiceAccountJwtTokenProvider provider = tokenProvider(stub.serviceAccountJson(), stub.tokenUri());

        assertThatThrownBy(provider::token)
                .isInstanceOfSatisfying(FcmAuthenticationException.class,
                        e -> assertThat(e.failureType()).isEqualTo(FailureType.UNKNOWN));
    }

    @Test
    void invalidate_forcesTheNextTokenCall_andCloseDropsTheCache() {
        ServiceAccountJwtTokenProvider provider = tokenProvider(stub.serviceAccountJson(), stub.tokenUri());
        FcmAccessToken first = provider.token();
        assertThat(provider.token()).isSameAs(first);

        provider.invalidate();
        assertThat(provider.token().value()).isNotEqualTo(first.value());
        provider.close();
        provider.token();

        assertThat(stub.tokenCalls()).isEqualTo(3);
        assertThat(first.toString()).doesNotContain(first.value()).contains("****");
    }

    @Test
    void credentialsFromAFile_andTheFilePrefix(@TempDir Path dir) throws Exception {
        Path key = dir.resolve("sa.json");
        Files.writeString(key, stub.serviceAccountJson(), StandardCharsets.UTF_8);

        assertThat(tokenProvider(key.toString(), stub.tokenUri()).token().value()).startsWith("stub-access-token-");
        assertThat(tokenProvider("file:" + key, stub.tokenUri()).clientEmail()).isEqualTo(FcmStubServer.CLIENT_EMAIL);
    }

    @Test
    void unusableKeys_failWithoutEchoingKeyMaterial(@TempDir Path dir) {
        String validJson = stub.serviceAccountJson();
        String secretBase64 = stub.privateKeyBase64().substring(0, 40);

        assertThatThrownBy(() -> tokenProvider(validJson.substring(0, validJson.indexOf("-----END")), null))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("is not valid JSON (line 1")
                .message().doesNotContain(secretBase64);
        assertThatThrownBy(() -> tokenProvider(validJson.replace("BEGIN PRIVATE KEY", "BEGIN RSA PRIVATE KEY"), null))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("PKCS#1");
        assertThatThrownBy(() -> tokenProvider(validJson.replace(secretBase64.substring(0, 20), "AAAA"), null))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("is not a PKCS#8 RSA key")
                .message().doesNotContain(secretBase64.substring(20));
        assertThatThrownBy(() -> tokenProvider("{\"type\":\"authorized_user\",\"client_id\":\"x\"}", null))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("only 'service_account' keys")
                .hasMessageContaining(FcmSettings.GOOGLE_AUTH_ARTIFACT);
        assertThatThrownBy(() -> tokenProvider("{\"type\":\"service_account\"}", null))
                .hasMessageContaining("needs 'client_email' and 'private_key'");
        assertThatThrownBy(() -> tokenProvider(dir.resolve("missing.json").toString(), null))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("cannot read the service-account file")
                .hasMessageContaining("missing.json");
    }
}
