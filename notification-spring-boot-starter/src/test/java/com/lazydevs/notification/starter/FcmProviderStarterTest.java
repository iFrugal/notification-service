package com.lazydevs.notification.starter;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventListener;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.delivery.DeliveryEvents;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.PushRecipient;
import com.lazydevs.notification.channel.push.fcm.FcmHttpRequest;
import com.lazydevs.notification.channel.push.fcm.FcmHttpResponse;
import com.lazydevs.notification.channel.push.fcm.FcmHttpTransport;
import com.lazydevs.notification.channel.push.fcm.FcmPushProvider;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The FCM provider as an application embedding the starter gets it: resolved by
 * the built-in name {@code fcm}, sending through an application-supplied
 * {@link FcmHttpTransport} bean (a fake FCM here, no network), and publishing an
 * {@code UNREGISTERED} token as a {@code BOUNCED} event that reaches the listeners
 * and the delivery-event store under the response's provider message id.
 */
class FcmProviderStarterTest {

    private static final String FCM = "notification.tenants.acme.channels.push.providers.fcm.properties.";
    private static final String LIVE_TOKEN = "live-registration-token-0123456789abcdef";
    private static final String DEAD_TOKEN = "dead-registration-token-0123456789abcdef";

    @TempDir
    static Path dir;
    private static Path key;

    private final WebApplicationContextRunner runner = StarterContextRunners.starterRunner();

    @BeforeAll
    static void writeServiceAccountKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\\n"
                + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\\n-----END PRIVATE KEY-----\\n";
        key = dir.resolve("firebase-sa.json");
        Files.writeString(key, """
                {"type":"service_account","project_id":"starter-project","private_key_id":"k1",
                 "private_key":"%s","client_email":"sender@starter-project.iam.gserviceaccount.com",
                 "token_uri":"https://oauth2.googleapis.com/token"}""".formatted(pem));
    }

    private static NotificationRequest push(String requestId, String token) {
        return NotificationRequest.builder()
                .requestId(requestId)
                .tenantId("acme")
                .notificationType("FCM_STARTER_TEST")
                .channel(Channel.PUSH)
                .templateData(Map.of("orderId", "42"))
                .recipient(new PushRecipient(null, token, null, null, "Shipped", null, null, null, null, null, null))
                .build();
    }

    @Test
    void builtInName_resolvesFcm_andSendsThroughTheTransportBean() {
        runner.withUserConfiguration(FakeFcmConfiguration.class)
                .withPropertyValues(
                        FCM + "credentials=" + key,
                        FCM + "endpoint=http://127.0.0.1:1",
                        FCM + "token-endpoint=http://127.0.0.1:1/token")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ProviderRegistry.class).getProvider("acme", Channel.PUSH, null))
                            .isInstanceOf(FcmPushProvider.class);

                    NotificationResponse response = context.getBean(NotificationService.class)
                            .send(push("req-fcm-1", LIVE_TOKEN));

                    assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
                    assertThat(response.providerMessageId()).isEqualTo("projects/starter-project/messages/1");
                    FakeFcm fake = context.getBean(FakeFcm.class);
                    assertThat(fake.tokenCalls.get()).isEqualTo(1);
                    assertThat(fake.sendBodies).singleElement().asString()
                            .contains("\"token\":\"" + LIVE_TOKEN + "\"",
                                    "\"title\":\"Shipped\"", "\"body\":\"Order 42 has shipped\"");
                });
    }

    @Test
    void unregisteredToken_isBounced_toTheListenerAndTheStore_andDeadLettered() {
        runner.withUserConfiguration(FakeFcmConfiguration.class, RecordingListenerConfiguration.class)
                .withPropertyValues(
                        FCM + "credentials=" + key,
                        FCM + "endpoint=http://127.0.0.1:1",
                        FCM + "token-endpoint=http://127.0.0.1:1/token",
                        "notification.delivery-events.enabled=true",
                        "notification.dead-letter.enabled=true",
                        "notification.retry.enabled=true",
                        "notification.retry.initial-delay=1ms",
                        "notification.retry.max-delay=5ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    NotificationResponse response = context.getBean(NotificationService.class)
                            .send(push("req-fcm-dead", DEAD_TOKEN));

                    assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
                    assertThat(response.errorCode()).isEqualTo("UNREGISTERED");
                    assertThat(response.providerMessageId()).startsWith("fcm-local:");
                    assertThat(context.getBean(FakeFcm.class).sendBodies).as("PERMANENT is not retried").hasSize(1);

                    List<DeliveryEvent> heard = RecordingListenerConfiguration.EVENTS;
                    assertThat(heard).singleElement().satisfies(event -> {
                        assertThat(event.status()).isEqualTo(DeliveryStatus.BOUNCED);
                        assertThat(event.reason()).isEqualTo(DeliveryEvents.REASON_INVALID_TARGET);
                        assertThat(event.providerName()).isEqualTo("fcm");
                        assertThat(event.providerMessageId()).isEqualTo(response.providerMessageId());
                        assertThat(event.attributes())
                                .containsEntry(DeliveryEvents.ATTR_TARGET_TYPE, DeliveryEvents.TARGET_TYPE_TOKEN)
                                .containsEntry(DeliveryEvents.ATTR_FAILURE, "UNREGISTERED")
                                .hasEntrySatisfying(DeliveryEvents.ATTR_TOKEN_HASH,
                                        hash -> assertThat(hash).matches("sha256:[0-9a-f]{16}"));
                        assertThat(event.toString()).doesNotContain(DEAD_TOKEN);
                    });
                    assertThat(context.getBean(DeliveryEventStore.class)
                            .findByProviderMessageId("fcm", response.providerMessageId()))
                            .hasValueSatisfying(events -> assertThat(events).containsExactlyElementsOf(heard));

                    DeadLetterEntry dead = context.getBean(DeadLetterStore.class)
                            .findByRequestId("acme", "req-fcm-dead").orElseThrow();
                    assertThat(dead.failureType()).isEqualTo(FailureType.PERMANENT);
                    assertThat(dead.attempts()).isEqualTo(1);
                });
    }

    @Test
    void adcWithoutTheAdapterModule_failsStartupNamingTheArtifact() {
        runner.withPropertyValues(FCM + "credentials=adc", FCM + "project-id=starter-project")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().rootCause()
                            .isInstanceOf(ProviderConfigurationException.class)
                            .hasMessageContaining("com.github.ifrugal:push-provider-fcm-google-auth");
                });
    }

    /** Answers the token call and the send call like Google and FCM would. */
    static final class FakeFcm implements FcmHttpTransport {
        final AtomicInteger tokenCalls = new AtomicInteger();
        final List<String> sendBodies = new CopyOnWriteArrayList<>();

        @Override
        public FcmHttpResponse execute(FcmHttpRequest request) {
            if (request.uri().getPath().equals("/token")) {
                tokenCalls.incrementAndGet();
                return json(200, "{\"access_token\":\"starter-access-token\",\"expires_in\":3600}");
            }
            String body = new String(request.body(), StandardCharsets.UTF_8);
            sendBodies.add(body);
            if (body.contains(DEAD_TOKEN)) {
                return json(404, "{\"error\":{\"code\":404,\"message\":\"Requested entity was not found.\","
                        + "\"status\":\"NOT_FOUND\",\"details\":[{\"@type\":"
                        + "\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"UNREGISTERED\"}]}}");
            }
            return json(200, "{\"name\":\"projects/starter-project/messages/" + sendBodies.size() + "\"}");
        }

        private static FcmHttpResponse json(int status, String body) {
            return new FcmHttpResponse(status, Map.of("Content-Type", List.of("application/json")),
                    body.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FakeFcmConfiguration {
        @Bean
        FakeFcm fakeFcm() {
            return new FakeFcm();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingListenerConfiguration {

        static final List<DeliveryEvent> EVENTS = new CopyOnWriteArrayList<>();

        @Bean
        DeliveryEventListener recordingListener() {
            EVENTS.clear();
            return EVENTS::add;
        }
    }
}
