package com.lazydevs.notification.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the springdoc-generated OpenAPI schema (Phase 9).
 *
 * <p>Boots the full Spring context and hits {@code /v3/api-docs} to
 * confirm:
 * <ul>
 *   <li>The endpoint serves a 200 response.</li>
 *   <li>The schema includes our key paths — {@code /api/v1/notifications}
 *       and the admin endpoints we documented.</li>
 *   <li>The OpenAPI metadata is populated from {@code OpenApiConfig}.</li>
 * </ul>
 *
 * <p>Uses the JDK's built-in {@link HttpClient} rather than Spring
 * Boot's {@code TestRestTemplate} — Boot 4 reorganised the test
 * web-client classpath, and the JDK option keeps the test dependency
 * footprint nil.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // Force REST + the schema endpoint on; Kafka, audit, and the
        // optional features stay disabled — keeps this test
        // self-contained and fast.
        "notification.rest.enabled=true",
        "notification.kafka.enabled=false",
        "notification.audit.enabled=false",
        "springdoc.api-docs.enabled=true",
        "logging.level.org.springframework=WARN",
        "logging.level.com.lazydevs.notification=WARN",
})
class OpenApiSmokeTest {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void apiDocs_endpointReturnsValidSchema() throws Exception {
        HttpResponse<String> response = get("/v3/api-docs");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .as("OpenAPI schema body should be non-empty JSON")
                .isNotBlank()
                .startsWith("{");

        // Persist the schema to target/openapi.json so the CI workflow
        // can upload it as a release artifact. Pretty-printed for human
        // diffability across PRs — generator output is otherwise minified.
        ObjectMapper mapper = new ObjectMapper();
        JsonNode schema = mapper.readTree(response.body());
        Path out = Paths.get("target", "openapi.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema));

        // OpenAPI version is the cheapest sanity check.
        assertThat(schema.get("openapi").asText())
                .as("OpenAPI version field")
                .startsWith("3.");

        // Service-level metadata from OpenApiConfig.
        JsonNode info = schema.get("info");
        assertThat(info.get("title").asText()).isEqualTo("Notification Service API");
        assertThat(info.get("description").asText())
                .as("Description should mention the cross-cutting headers")
                .contains("X-Tenant-Id", "X-Service-Id", "X-Idempotent-Replay", "Retry-After");

        // Key paths must be present — these are the documented contracts
        // clients depend on.
        JsonNode paths = schema.get("paths");
        assertThat(paths.fieldNames())
                .toIterable()
                .as("schema paths")
                .contains(
                        "/api/v1/notifications",
                        "/api/v1/notifications/batch",
                        "/api/v1/notifications/async",
                        "/api/v1/admin/configuration",
                        "/api/v1/admin/caller-registry",
                        "/api/v1/admin/rate-limit",
                        "/api/v1/admin/dead-letter");
    }

    @Test
    void swaggerUi_endpointResponds() throws Exception {
        // Springdoc redirects /swagger-ui.html → /swagger-ui/index.html.
        // Hitting the redirect target directly avoids depending on the
        // client's redirect-following behaviour.
        HttpResponse<String> response = get("/swagger-ui/index.html");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .as("swagger-ui index should contain a script tag for swagger-initializer")
                .contains("swagger-initializer");
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
