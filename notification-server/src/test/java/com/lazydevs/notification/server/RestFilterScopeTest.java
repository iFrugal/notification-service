package com.lazydevs.notification.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Over real HTTP, proves the notification filters run on the REST API and
 * only there: a strict caller registry rejects an unknown caller on
 * {@code /api/v1/**}, while the same request header on an actuator path is
 * not inspected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "notification.kafka.enabled=false",
        "notification.audit.enabled=false",
        "notification.caller-registry.enabled=true",
        "notification.caller-registry.strict=true",
        "notification.caller-registry.known-services=billing-svc",
        "logging.level.org.springframework=WARN",
        "logging.level.com.lazydevs.notification=WARN",
})
class RestFilterScopeTest {

    private static final String UNKNOWN_CALLER = "rogue-svc";

    /** The documented spelling; Tomcat passes HTTP/1.1 header names through as sent. */
    private static final String CALLER_HEADER = "X-Service-Id";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void unknownCallerOnRestApi_isRejectedByAdmissionFilter() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/notifications"))
                .header("Content-Type", "application/json")
                .header(CALLER_HEADER, UNKNOWN_CALLER)
                .POST(HttpRequest.BodyPublishers.ofString("{}")));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("unknown_caller", UNKNOWN_CALLER);
    }

    @Test
    void unknownCallerOutsideRestApi_isNotFiltered() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/actuator/health"))
                .header(CALLER_HEADER, UNKNOWN_CALLER)
                .GET());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
