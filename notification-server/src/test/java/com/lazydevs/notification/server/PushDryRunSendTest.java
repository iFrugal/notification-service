package com.lazydevs.notification.server;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.PushRecipient;
import com.lazydevs.notification.channel.push.fcm.FcmPushProvider;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The standalone server with push switched on as an operator would, through the
 * environment variables its {@code application.yml} reads ({@code PUSH_ENABLED},
 * {@code FCM_CREDENTIALS}, the deprecated {@code FCM_CREDENTIALS_PATH}), and a
 * {@code dry-run} FCM tenant, so one push goes through {@link NotificationService}
 * without any network call.
 */
class PushDryRunSendTest {

    private static final String FCM = "notification.tenants.default.channels.push.providers.fcm.properties.";

    @TempDir
    static Path dir;
    private static String serviceAccountJson;
    private static Path serviceAccountFile;

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(NotificationServerApplication.class)
            .withPropertyValues(
                    "notification.kafka.enabled=false",
                    "notification.audit.enabled=false",
                    "PUSH_ENABLED=true",
                    FCM + "dry-run=true");

    @BeforeAll
    static void serviceAccountKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\\n"
                + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\\n-----END PRIVATE KEY-----\\n";
        serviceAccountJson = """
                {"type":"service_account","project_id":"server-project","private_key_id":"k1",\
                "private_key":"%s","client_email":"sender@server-project.iam.gserviceaccount.com",\
                "token_uri":"https://oauth2.googleapis.com/token"}""".formatted(pem);
        serviceAccountFile = Files.writeString(dir.resolve("firebase-credentials.json"), serviceAccountJson);
    }

    private static NotificationRequest push() {
        return NotificationRequest.builder()
                .requestId("server-push-1")
                .tenantId("default")
                .notificationType("SERVER_PUSH_SMOKE")
                .channel(Channel.PUSH)
                .templateData(Map.of("orderId", "A-1001"))
                .recipient(new PushRecipient(null, "server-registration-token-0123456789", null, null, "Shipped",
                        null, null, null, null, null, null))
                .build();
    }

    @Test
    void inlineCredentialsFromTheEnvironment_sendOnePushInDryRun() {
        runner.withPropertyValues("FCM_CREDENTIALS=" + serviceAccountJson)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FcmPushProvider provider = (FcmPushProvider) context.getBean(ProviderRegistry.class)
                            .getProvider("default", Channel.PUSH, null);
                    assertThat(provider.getHealthDetails())
                            .containsEntry("credentials", "service-account-json (inline)")
                            .containsEntry("projectId", "server-project")
                            .containsEntry("dryRun", true);

                    NotificationResponse response = context.getBean(NotificationService.class).send(push());

                    assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
                    assertThat(response.provider()).isEqualTo("fcm");
                    assertThat(response.providerMessageId()).startsWith(FcmPushProvider.DRY_RUN_ID_PREFIX);
                });
    }

    @Test
    void deprecatedCredentialsPathVariable_stillNamesTheKeyFile() {
        runner.withPropertyValues("FCM_CREDENTIALS_PATH=" + serviceAccountFile)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ProviderRegistry.class).getProvider("default", Channel.PUSH, null)
                            .getHealthDetails())
                            .containsEntry("credentials", "service-account-file:" + serviceAccountFile);

                    assertThat(context.getBean(NotificationService.class).send(push()).status())
                            .isEqualTo(NotificationStatus.SENT);
                });
    }
}
