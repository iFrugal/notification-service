package com.lazydevs.notification.rest.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 service-level metadata for the notification REST API.
 *
 * <p>Springdoc generates the schema by introspecting controllers; this
 * configuration adds the metadata that can't be derived (title, version,
 * contact) and registers reusable component schemas for the
 * notification-service-specific HTTP headers introduced across DD-10 /
 * DD-11 / DD-12 / DD-13:
 *
 * <ul>
 *   <li>{@code X-Tenant-Id} (DD-03)</li>
 *   <li>{@code X-Service-Id} (DD-11)</li>
 *   <li>{@code X-Idempotent-Replay} response header (DD-10)</li>
 *   <li>{@code Retry-After} response header (DD-12)</li>
 * </ul>
 *
 * <p>Bean is gated on {@code springdoc.api-docs.enabled} (the standard
 * springdoc property) so deployments that opt out of the schema endpoint
 * also opt out of building this metadata. Default is on — same as
 * springdoc itself.
 */
@Configuration
@ConditionalOnProperty(prefix = "springdoc.api-docs", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OpenApiConfig {

    /** Header name constants — referenced from controller annotations. */
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    public static final String HEADER_SERVICE_ID = "X-Service-Id";
    public static final String HEADER_IDEMPOTENT_REPLAY = "X-Idempotent-Replay";
    public static final String HEADER_RETRY_AFTER = "Retry-After";

    @Value("${notification.openapi.version:1.0.1-SNAPSHOT}")
    private String apiVersion;

    @Bean
    public OpenAPI notificationServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Notification Service API")
                        .version(apiVersion)
                        .description("""
                                Multi-tenant notification service supporting email, SMS,
                                WhatsApp, and push channels with first-class
                                idempotency, rate limiting, retries, and dead-letter
                                handling.

                                ## Cross-cutting headers

                                * **X-Tenant-Id** (DD-03) — tenant identifier on the
                                  request. When absent, the service falls back to the
                                  configured `notification.default-tenant`.
                                * **X-Service-Id** (DD-11) — calling-service identifier.
                                  Optional; participates in the idempotency dedup
                                  tuple when both X-Service-Id and `idempotencyKey`
                                  are present.
                                * **X-Idempotent-Replay** (DD-10, response only) —
                                  set to `true` when the response is being served
                                  from the idempotency cache rather than from a
                                  fresh provider call.
                                * **Retry-After** (DD-12, response only) — present
                                  on `429 Too Many Requests` responses; whole
                                  seconds per RFC 7231 §7.1.3.

                                ## Status codes you'll see

                                * **200** — successful send (or replayed cache hit).
                                * **202** — async send accepted; final outcome will
                                  be reflected in the audit record.
                                * **409** — idempotency conflict, another request
                                  with the same key is in flight (DD-10).
                                * **429** — rate limit exhausted (DD-12); Retry-After
                                  header tells you when to try again.
                                * **403** — caller-id rejected by strict caller
                                  registry (DD-11).
                                * **503** — admin endpoint requested but the backing
                                  feature is disabled (e.g. `/admin/dead-letter` when
                                  `notification.dead-letter.enabled=false`).
                                """)
                        .contact(new Contact()
                                .name("iFrugal")
                                .url("https://github.com/iFrugal/notification-service"))
                        .license(new License()
                                .name("Apache License 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .components(new Components()
                        .addParameters("XTenantId", tenantHeader())
                        .addParameters("XServiceId", callerHeader())
                        .addHeaders("XIdempotentReplay", idempotentReplayHeader())
                        .addHeaders("RetryAfter", retryAfterHeader()));
    }

    private static HeaderParameter tenantHeader() {
        return (HeaderParameter) new HeaderParameter()
                .name(HEADER_TENANT_ID)
                .description("Tenant identifier (DD-03). When absent, falls back to "
                        + "the configured default tenant.")
                .required(false)
                .schema(new StringSchema().example("acme"));
    }

    private static HeaderParameter callerHeader() {
        return (HeaderParameter) new HeaderParameter()
                .name(HEADER_SERVICE_ID)
                .description("Calling-service identifier (DD-11). Optional; used "
                        + "by the idempotency dedup scope and the audit trail.")
                .required(false)
                .schema(new StringSchema().example("billing-svc").maxLength(128));
    }

    private static Header idempotentReplayHeader() {
        return new Header()
                .description("Set to `true` when the response is served from the "
                        + "idempotency cache rather than from a fresh provider call "
                        + "(DD-10 §REST-API-behaviour).")
                .schema(boolHeaderSchema());
    }

    private static Header retryAfterHeader() {
        return new Header()
                .description("Whole-seconds delay before the caller should retry, "
                        + "per RFC 7231 §7.1.3. Present on 429 responses (DD-12).")
                .schema(new Schema<Integer>().type("integer").example(3));
    }

    private static Schema<?> boolHeaderSchema() {
        Schema<String> s = new StringSchema();
        s.setExample("true");
        return s;
    }
}
