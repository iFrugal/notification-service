package com.lazydevs.notification.channel.push.fcm;

import com.lazydevs.notification.channel.push.fcm.FcmSettings.MultiTokenPolicy;
import com.lazydevs.notification.channel.push.fcm.FcmSettings.TimeoutClassification;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FcmSettings} defaults and parsing, and {@link JdkFcmHttpTransport}'s
 * mapping of failures to {@code requestSent}.
 */
class FcmSettingsAndTransportTest {

    @Test
    void defaults() {
        FcmSettings settings = FcmSettings.fromMap(null);

        assertThat(settings.projectId()).isNull();
        assertThat(settings.credentials()).isNull();
        assertThat(settings.validateOnly()).isFalse();
        assertThat(settings.dryRun()).isFalse();
        assertThat(settings.timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.timeoutClassification()).isEqualTo(TimeoutClassification.AMBIGUOUS);
        assertThat(settings.concurrency()).isEqualTo(8);
        assertThat(settings.multiTokenPolicy()).isEqualTo(MultiTokenPolicy.ALL);
        assertThat(settings.maxTokens()).isEqualTo(500);
        assertThat(settings.logTokenHash()).isTrue();
        assertThat(settings.tokenRefreshMargin()).isEqualTo(Duration.ofMinutes(5));
        assertThat(settings.endpoint()).isEqualTo(URI.create("https://fcm.googleapis.com"));
        assertThat(settings.tokenEndpoint()).isNull();
        assertThat(settings.httpTransport()).isNull();
        assertThat(settings.androidDefaults()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"500ms, PT0.5S", "10s, PT10S", "2m, PT2M", "1h, PT1H", "PT15S, PT15S"})
    void durations(String value, String expected) {
        assertThat(FcmSettings.fromMap(Map.of("timeout", value)).timeout()).isEqualTo(Duration.parse(expected));
    }

    @Test
    void endpoints_acceptHttpsAndLoopbackHttp_andDropTrailingSlashes() {
        assertThat(FcmSettings.fromMap(Map.of("endpoint", "https://fcm.example.com/")).endpoint())
                .hasToString("https://fcm.example.com");
        for (String loopback : List.of("http://localhost:8080", "http://127.0.0.1:9", "http://[::1]:9")) {
            assertThat(FcmSettings.fromMap(Map.of("endpoint", loopback)).endpoint()).hasToString(loopback);
        }
        assertThatThrownBy(() -> FcmSettings.fromMap(Map.of("endpoint", "https://user:pw@fcm.example.com")))
                .hasMessageContaining("must not have user info");
    }

    @Test
    void platformDefaults_fromDottedKeys_andHeaderValuesBecomeStrings() {
        FcmSettings settings = FcmSettings.fromMap(Map.of(
                "android.priority", "high",
                "android.notification.channel_id", "orders",
                "apns.headers.apns-priority", 10,
                "webpush.headers.TTL", 60));

        assertThat(settings.androidDefaults().toString())
                .contains("\"priority\":\"HIGH\"", "\"channel_id\":\"orders\"");
        assertThat(settings.apnsDefaults().toString()).isEqualTo("{\"headers\":{\"apns-priority\":\"10\"}}");
        assertThat(settings.webpushDefaults().toString()).isEqualTo("{\"headers\":{\"TTL\":\"60\"}}");
        // Accessors hand out copies.
        settings.androidDefaults().put("priority", "NORMAL");
        assertThat(settings.androidDefaults().get("priority").asText()).isEqualTo("HIGH");
    }

    @Test
    void describeCredentials_neverShowsInlineJson() {
        assertThat(FcmSettings.describeCredentials("{\"private_key\":\"secret\"}"))
                .isEqualTo("service-account-json (inline)");
        assertThat(FcmSettings.describeCredentials("adc")).isEqualTo("adc");
        assertThat(FcmSettings.describeCredentials("external-account:/etc/wif.json"))
                .isEqualTo("external-account:/etc/wif.json");
        assertThat(FcmSettings.describeCredentials("/etc/sa.json")).isEqualTo("service-account-file:/etc/sa.json");
    }

    @Test
    void jdkTransport_connectionRefused_isNotSent() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        FcmHttpRequest request = new FcmHttpRequest("POST", URI.create("http://127.0.0.1:" + closedPort + "/x"),
                Map.of(), new byte[0], Duration.ofSeconds(2));

        assertThatThrownBy(() -> JdkFcmHttpTransport.shared().execute(request))
                .isInstanceOfSatisfying(FcmTransportException.class, e -> {
                    assertThat(e.requestSent()).isFalse();
                    assertThat(e.timeout()).isFalse();
                });
    }

    @Test
    void jdkTransport_timeout_isSentAndTimedOut_andAnswersCarryCaseInsensitiveHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/slow", exchange -> {
            try (exchange) {
                Thread.sleep(1500);
                exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.createContext("/ok", exchange -> {
            try (exchange) {
                byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Retry-After", "7");
                exchange.sendResponseHeaders(429, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            FcmHttpRequest slow = new FcmHttpRequest("POST", URI.create(base + "/slow"), Map.of(), new byte[0],
                    Duration.ofMillis(200));
            assertThatThrownBy(() -> JdkFcmHttpTransport.shared().execute(slow))
                    .isInstanceOfSatisfying(FcmTransportException.class, e -> {
                        assertThat(e.requestSent()).isTrue();
                        assertThat(e.timeout()).isTrue();
                    });

            FcmHttpResponse response = JdkFcmHttpTransport.shared().execute(new FcmHttpRequest("POST",
                    URI.create(base + "/ok"), Map.of("Content-Type", "application/json"), "{}".getBytes(),
                    Duration.ofSeconds(2)));
            assertThat(response.statusCode()).isEqualTo(429);
            assertThat(response.header("retry-after")).isEqualTo("7");
            assertThat(response.bodyAsString()).isEqualTo("{\"ok\":true}");
            assertThat(response.isSuccess()).isFalse();
        } finally {
            server.stop(0);
        }
    }
}
