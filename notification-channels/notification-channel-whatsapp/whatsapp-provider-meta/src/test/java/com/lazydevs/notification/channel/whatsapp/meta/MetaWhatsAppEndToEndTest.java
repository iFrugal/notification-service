package com.lazydevs.notification.channel.whatsapp.meta;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventListener;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.exception.TemplateNotFoundException;
import com.lazydevs.notification.api.exception.TemplateValidationException;
import com.lazydevs.notification.api.guard.RecipientGuard;
import com.lazydevs.notification.api.idempotency.IdempotencyReleasePolicy;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.NotificationView;
import com.lazydevs.notification.api.model.WhatsAppRecipient;
import com.lazydevs.notification.api.webhook.WebhookRequest;
import com.lazydevs.notification.api.webhook.WebhookResponse;
import com.lazydevs.notification.channel.whatsapp.WhatsAppChannelAutoConfiguration;
import com.lazydevs.notification.channel.whatsapp.meta.MetaStubServer.StubResponse;
import com.lazydevs.notification.core.config.NotificationCoreAutoConfiguration;
import com.lazydevs.notification.core.config.NotificationCoreDefaultsAutoConfiguration;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.idempotency.FailurePolicyReleasePolicy;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.webhook.WebhookDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole path, as an application sees it: {@code NotificationService.send}
 * resolves the YAML template binding, the {@code WHATSAPP} renderer builds the
 * template message, {@code ProviderRegistry} configures {@code WHATSAPP:meta}
 * from the tenant's properties, and the provider talks to {@link MetaStubServer}.
 * The status webhook then comes back through the {@link WebhookDispatcher}.
 */
class MetaWhatsAppEndToEndTest {

    private static final String PREFIX = "notification.tenants.default.channels.whatsapp.";
    private static final String TEMPLATE = "notification.whatsapp.templates.default.order-shipped.";
    private static final String SECRET = "your-app-secret";

    private final MetaStubServer stub = new MetaStubServer();

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        NotificationCoreAutoConfiguration.class,
                        NotificationCoreDefaultsAutoConfiguration.class,
                        WhatsAppChannelAutoConfiguration.class,
                        MetaWhatsAppProviderAutoConfiguration.class))
                .withUserConfiguration(RecordingListenerConfig.class)
                .withPropertyValues(
                        "notification.default-tenant=default",
                        PREFIX + "enabled=true",
                        PREFIX + "providers.meta.properties.base-url=" + stub.baseUri(),
                        PREFIX + "providers.meta.properties.access-token=" + MetaStubServer.ACCESS_TOKEN,
                        PREFIX + "providers.meta.properties.phone-number-id=" + MetaStubServer.PHONE_NUMBER_ID,
                        PREFIX + "providers.meta.properties.timeout=500ms",
                        TEMPLATE + "name=order_shipped",
                        TEMPLATE + "language=en",
                        TEMPLATE + "body[0]=customerName",
                        TEMPLATE + "body[1]=orderNumber",
                        TEMPLATE + "buttons[0].index=0",
                        TEMPLATE + "buttons[0].sub-type=url",
                        TEMPLATE + "buttons[0].variable=trackingToken",
                        "notification.webhooks.meta.enabled=true",
                        "notification.webhooks.meta.app-secret=" + SECRET,
                        "notification.webhooks.meta.verify-token=your-verify-token");
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private static NotificationRequest order() {
        Map<String, Object> data = new HashMap<>();
        data.put("customerName", "Jane");
        data.put("orderNumber", 1042);
        data.put("trackingToken", "abc123");
        return NotificationRequest.builder()
                .requestId("req-e2e-1")
                .notificationType("order-shipped")
                .channel(Channel.WHATSAPP)
                .recipient(new WhatsAppRecipient(null, MetaTestSupport.PHONE, null, null, null, null, null))
                .templateData(data)
                .build();
    }

    private static NotificationResponse send(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed();
        return context.getBean(NotificationService.class).send(order());
    }

    @Test
    void send_resolvesTheBinding_andMetaAcceptsIt() {
        runner().run(context -> {
            NotificationResponse response = send(context);

            assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
            assertThat(response.provider()).isEqualTo("meta");
            assertThat(response.providerMessageId()).isEqualTo("wamid.STUB1");
            MetaStubServer.SendRequest sent = stub.requests().getFirst();
            assertThat(sent.body().path("to").asText()).isEqualTo("15550100001");
            assertThat(sent.body().path("biz_opaque_callback_data").asText()).isEqualTo("req-e2e-1");
            assertThat(MetaJson.writeString(sent.body().path("template"))).isEqualTo(
                    "{\"name\":\"order_shipped\",\"language\":{\"code\":\"en\"},\"components\":["
                            + "{\"type\":\"body\",\"parameters\":[{\"type\":\"text\",\"text\":\"Jane\"},"
                            + "{\"type\":\"text\",\"text\":\"1042\"}]},"
                            + "{\"type\":\"button\",\"sub_type\":\"url\",\"index\":\"0\","
                            + "\"parameters\":[{\"type\":\"text\",\"text\":\"abc123\"}]}]}");
        });
    }

    @Test
    void badTemplateValue_failsBeforeMetaIsCalled() {
        runner().run(context -> {
            NotificationRequest request = order();
            request.getTemplateData().put("customerName", "Jane\nDoe");

            NotificationResponse response = context.getBean(NotificationService.class).send(request);

            assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
            assertThat(response.errorCode()).isEqualTo("TEMPLATE_VALIDATION_FAILED");
            assertThat(stub.requests()).isEmpty();
        });
    }

    @Test
    void graphErrors_surfaceTheirContractErrorCodes() {
        stub.enqueue(
                StubResponse.of(429, MetaStubServer.graphError(130429, "Rate limit hit")).withHeader("Retry-After", "3"),
                StubResponse.of(400, MetaStubServer.graphError(131056, "Pair rate limit hit")),
                StubResponse.of(500, MetaStubServer.graphError(131000, "Something went wrong")),
                StubResponse.of(404, MetaStubServer.graphError(132001, "Template name does not exist")),
                StubResponse.of(200, MetaStubServer.accepted("wamid.LATE")).after(Duration.ofSeconds(3)));
        runner().run(context -> {
            List<String> codes = List.of(send(context).errorCode(), send(context).errorCode(),
                    send(context).errorCode(), send(context).errorCode(), send(context).errorCode());

            assertThat(codes).containsExactly(MetaErrorCodes.THROTTLED, MetaErrorCodes.PAIR_RATE_LIMITED,
                    MetaErrorCodes.PROVIDER_UNAVAILABLE, MetaErrorCodes.TEMPLATE_REJECTED,
                    MetaErrorCodes.NO_CONFIRMATION);
        });
    }

    @Test
    void rejectedToken_failsTheSend_andTheProviderReportsDown() {
        runner().withPropertyValues(PREFIX + "providers.meta.properties.access-token=expired-token")
                .run(context -> {
                    NotificationResponse response = send(context);

                    assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
                    assertThat(response.errorCode()).isEqualTo(MetaErrorCodes.CREDENTIAL_REJECTED);
                    assertThat(context.getBean(ProviderRegistry.class)
                            .getProvider("default", Channel.WHATSAPP, "meta").isHealthy()).isFalse();
                });
    }

    @Test
    void withRetries_aThrottleIsRetriedAfterRetryAfter_butAnAmbiguousTimeoutIsNot() {
        stub.enqueue(
                StubResponse.of(429, MetaStubServer.graphError(130429, "Rate limit hit")).withHeader("Retry-After", "1"),
                StubResponse.of(200, MetaStubServer.accepted("wamid.RETRIED")),
                StubResponse.of(200, MetaStubServer.accepted("wamid.LATE")).after(Duration.ofSeconds(3)));
        runner().withPropertyValues("notification.retry.enabled=true", "notification.retry.initial-delay=10ms")
                .run(context -> {
                    long started = System.nanoTime();
                    NotificationResponse retried = send(context);
                    Duration took = Duration.ofNanos(System.nanoTime() - started);

                    assertThat(retried.status()).isEqualTo(NotificationStatus.SENT);
                    assertThat(retried.providerMessageId()).isEqualTo("wamid.RETRIED");
                    assertThat(took).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
                    assertThat(stub.requests()).hasSize(2);

                    NotificationResponse ambiguous = send(context);
                    assertThat(ambiguous.errorCode()).isEqualTo(MetaErrorCodes.NO_CONFIRMATION);
                    assertThat(stub.requests()).hasSize(3);
                });
    }

    @Test
    void statusWebhook_reachesTheListeners_throughTheDispatcher() {
        runner().run(context -> {
            String body = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{"
                    + "\"metadata\":{\"phone_number_id\":\"" + MetaStubServer.PHONE_NUMBER_ID + "\"},"
                    + "\"statuses\":[{\"id\":\"wamid.STUB1\",\"status\":\"read\",\"timestamp\":\"1767225602\","
                    + "\"recipient_id\":\"15550100001\",\"biz_opaque_callback_data\":\"req-e2e-1\"}]}}]}]}";
            WebhookRequest request = new WebhookRequest("POST", "",
                    Map.of(MetaWebhookHandler.SIGNATURE_HEADER, List.of(MetaWebhookHandlerTest.sign(body, SECRET))),
                    Map.of(), body.getBytes(StandardCharsets.UTF_8), null);

            WebhookResponse response = context.getBean(WebhookDispatcher.class).dispatch("meta", request);

            assertThat(response.status()).isEqualTo(200);
            List<DeliveryEvent> received = context.getBean(RecordingListenerConfig.class).events;
            assertThat(received).singleElement().satisfies(event -> {
                assertThat(event.status()).isEqualTo(DeliveryStatus.READ);
                assertThat(event.providerMessageId()).isEqualTo("wamid.STUB1");
                assertThat(event.attributes()).containsEntry("biz_opaque_callback_data", "req-e2e-1");
            });
        });
    }

    // ---- 1.3.0 send pipeline (DD-27, DD-31, DD-32, DD-33, DD-34) with the WhatsApp channel ----

    private static NotificationRequest keyed(NotificationRequest request, String idempotencyKey) {
        return request.toBuilder().idempotencyKey(idempotencyKey).build();
    }

    @Test
    void validateBeforeDispatch_badTemplateValue_isThrownBeforeTheClaim_andNothingIsStored() {
        runner().withPropertyValues("notification.template.validate-before-dispatch=true",
                        "notification.idempotency.enabled=true", "notification.audit.enabled=true")
                .run(context -> {
                    NotificationService service = context.getBean(NotificationService.class);
                    NotificationRequest bad = keyed(order(), "key-1");
                    bad.getTemplateData().put("customerName", "Jane\nDoe");

                    assertThatThrownBy(() -> service.send(bad))
                            .isInstanceOf(TemplateValidationException.class)
                            .hasMessageContaining("customerName")
                            .hasMessageNotContaining("Jane");
                    assertThat(stub.requests()).isEmpty();
                    assertThat(service.find("default", "req-e2e-1")).isEmpty();

                    // The key was never claimed, so the corrected request under it is sent.
                    NotificationResponse fixed = service.send(keyed(order(), "key-1"));
                    assertThat(fixed.status()).isEqualTo(NotificationStatus.SENT);
                    assertThat(fixed.idempotentReplay()).isNotEqualTo(Boolean.TRUE);
                    assertThat(stub.requests()).hasSize(1);
                });
    }

    @Test
    void validateBeforeDispatch_unboundTemplateWithoutFtl_isTemplateNotFound_beforeTheClaim() {
        runner().withPropertyValues("notification.template.validate-before-dispatch=true",
                        "notification.idempotency.enabled=true", "notification.audit.enabled=true")
                .run(context -> {
                    NotificationService service = context.getBean(NotificationService.class);
                    NotificationRequest unbound = keyed(order(), "key-2").toBuilder()
                            .notificationType("no-such-template").build();

                    assertThatThrownBy(() -> service.send(unbound)).isInstanceOf(TemplateNotFoundException.class);
                    assertThat(stub.requests()).isEmpty();
                    assertThat(service.find("default", "req-e2e-1")).isEmpty();
                });
    }

    @Test
    void retryAfterHints_reachTheResponse() {
        stub.enqueue(
                StubResponse.of(429, MetaStubServer.graphError(130429, "Rate limit hit")).withHeader("Retry-After", "3"),
                StubResponse.of(400, MetaStubServer.graphError(131056, "Pair rate limit hit")));
        runner().run(context -> {
            NotificationResponse throttled = send(context);
            NotificationResponse pairLimited = send(context);

            assertThat(throttled.errorCode()).isEqualTo(MetaErrorCodes.THROTTLED);
            assertThat(throttled.failureType()).isEqualTo(FailureType.TRANSIENT);
            assertThat(throttled.retryAfter()).isEqualTo(Duration.ofSeconds(3));
            assertThat(pairLimited.errorCode()).isEqualTo(MetaErrorCodes.PAIR_RATE_LIMITED);
            assertThat(pairLimited.retryAfter()).isEqualTo(Duration.ofSeconds(6));
        });
    }

    @Test
    void recipientGuard_redirectsAWhatsAppNumber_beforeMetaIsCalled() {
        runner().withPropertyValues("notification.recipient-guard.enabled=true",
                        "notification.recipient-guard.channels.whatsapp.allowed[0]=+1 555 0100 999",
                        "notification.recipient-guard.channels.whatsapp.redirect-to=+15550100999")
                .run(context -> {
                    NotificationResponse response = send(context);

                    assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
                    assertThat(stub.requests()).singleElement()
                            .satisfies(sent -> assertThat(sent.body().path("to").asText()).isEqualTo("15550100999"));
                });
    }

    @Test
    void recipientGuard_withoutRedirect_blocksANumberThatIsNotAllowed() {
        runner().withPropertyValues("notification.recipient-guard.enabled=true",
                        "notification.recipient-guard.channels.whatsapp.allowed[0]=+15550100999")
                .run(context -> {
                    NotificationResponse response = send(context);

                    assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
                    assertThat(response.errorCode()).isEqualTo(RecipientGuard.BLOCKED_ERROR_CODE);
                    assertThat(stub.requests()).isEmpty();
                });
    }

    @Test
    void credentialRejected_isPermanent_soReleaseTransientKeepsTheKey() {
        runner().withPropertyValues(PREFIX + "providers.meta.properties.access-token=expired-token",
                        "notification.idempotency.enabled=true",
                        "notification.idempotency.failure-policy=RELEASE_TRANSIENT")
                .run(context -> {
                    NotificationService service = context.getBean(NotificationService.class);

                    assertThat(service.send(keyed(order(), "key-3")).errorCode())
                            .isEqualTo(MetaErrorCodes.CREDENTIAL_REJECTED);
                    NotificationResponse again = service.send(keyed(order(), "key-3"));

                    assertThat(again.idempotentReplay()).isTrue();
                    assertThat(again.errorCode()).isEqualTo(MetaErrorCodes.CREDENTIAL_REJECTED);
                    assertThat(stub.requests()).hasSize(1);
                });
    }

    @Test
    void credentialRejected_isReleasedByTheDeploymentsOwnReleasePolicy() {
        runner().withUserConfiguration(CredentialReleaseConfig.class)
                .withPropertyValues(PREFIX + "providers.meta.properties.access-token=expired-token",
                        "notification.idempotency.enabled=true",
                        "notification.idempotency.failure-policy=RELEASE_TRANSIENT")
                .run(context -> {
                    NotificationService service = context.getBean(NotificationService.class);

                    assertThat(service.send(keyed(order(), "key-4")).errorCode())
                            .isEqualTo(MetaErrorCodes.CREDENTIAL_REJECTED);
                    NotificationResponse again = service.send(keyed(order(), "key-4"));

                    assertThat(again.idempotentReplay()).isNotEqualTo(Boolean.TRUE);
                    assertThat(stub.requests()).hasSize(2);
                });
    }

    @Test
    void statusView_readBeforeDelivered_showsReadAndTheDeliveryTime() {
        runner().withPropertyValues("notification.audit.enabled=true", "notification.delivery-events.enabled=true")
                .run(context -> {
                    assertThat(send(context).status()).isEqualTo(NotificationStatus.SENT);
                    WebhookDispatcher dispatcher = context.getBean(WebhookDispatcher.class);

                    assertThat(dispatcher.dispatch("meta", status("sent", 1767225600)).status()).isEqualTo(200);
                    assertThat(dispatcher.dispatch("meta", status("read", 1767225602)).status()).isEqualTo(200);
                    assertThat(dispatcher.dispatch("meta", status("delivered", 1767225601)).status()).isEqualTo(200);

                    NotificationView view = context.getBean(NotificationService.class)
                            .find("default", "req-e2e-1").orElseThrow();
                    assertThat(view.deliveryStatus()).isEqualTo("READ");
                    assertThat(view.deliveryStatusAt()).isEqualTo(Instant.ofEpochSecond(1767225602));
                    assertThat(view.deliveredAt()).isEqualTo(Instant.ofEpochSecond(1767225601));
                });
    }

    private static WebhookRequest status(String status, long epochSecond) throws Exception {
        String body = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{"
                + "\"metadata\":{\"phone_number_id\":\"" + MetaStubServer.PHONE_NUMBER_ID + "\"},"
                + "\"statuses\":[{\"id\":\"wamid.STUB1\",\"status\":\"" + status + "\",\"timestamp\":\""
                + epochSecond + "\",\"recipient_id\":\"15550100001\"}]}}]}]}";
        return new WebhookRequest("POST", "",
                Map.of(MetaWebhookHandler.SIGNATURE_HEADER, List.of(MetaWebhookHandlerTest.sign(body, SECRET))),
                Map.of(), body.getBytes(StandardCharsets.UTF_8), null);
    }

    /** The release policy of the module README: also release the key after a rejected access token. */
    @Configuration(proxyBeanMethods = false)
    static class CredentialReleaseConfig {
        @Bean
        IdempotencyReleasePolicy releasePolicy(NotificationProperties properties) {
            IdempotencyReleasePolicy configured = new FailurePolicyReleasePolicy(properties);
            return (request, failed) -> configured.releases(request, failed)
                    || (failed.channel() == Channel.WHATSAPP
                        && MetaErrorCodes.CREDENTIAL_REJECTED.equals(failed.errorCode()));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingListenerConfig {
        final List<DeliveryEvent> events = new CopyOnWriteArrayList<>();

        @Bean
        DeliveryEventListener recordingListener() {
            return events::add;
        }
    }
}
