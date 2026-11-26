package com.lazydevs.notification.starter;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.channel.email.acs.AcsEmailProvider;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.provider.ProviderRegistry.PendingState;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-29 with the real ACS email provider bean: an application whose ACS
 * provider has no endpoint or sender cannot start with eager initialisation,
 * starts with {@code notification.providers.lazy-init=true}, fails the first
 * send as a {@code TRANSIENT} {@code PROVIDER_NOT_CONFIGURED} failure, and
 * builds the provider once its settings exist.
 */
class LazyProviderInitStarterTest {

    private static final String ACS = "notification.tenants.acme.channels.email.providers.acs.";

    private final WebApplicationContextRunner runner = StarterContextRunners.starterRunner()
            .withPropertyValues(ACS + "bean-name=acsEmailProvider");

    @Test
    void eager_acsWithoutSettings_failsStartup() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context).getFailure().hasRootCauseInstanceOf(ProviderConfigurationException.class);
        });
    }

    @Test
    void lazy_acsWithoutSettings_startsAndTheFirstSendFailsTransient() {
        runner.withPropertyValues(
                        "notification.providers.lazy-init=true",
                        "notification.dead-letter.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProviderRegistry registry = context.getBean(ProviderRegistry.class);
                    assertThat(registry.getAllProviders()).isEmpty();
                    assertThat(registry.getUninitializedProviders())
                            .containsExactlyEntriesOf(Map.of("acme:email:acs", PendingState.NOT_INITIALIZED));

                    NotificationResponse response = context.getBean(NotificationService.class)
                            .send(request("req-lazy-1"));

                    assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
                    assertThat(response.errorCode()).isEqualTo(ProviderRegistry.PROVIDER_NOT_CONFIGURED);
                    DeadLetterEntry dead = context.getBean(DeadLetterStore.class)
                            .findByRequestId("acme", "req-lazy-1").orElseThrow();
                    assertThat(dead.failureType()).isEqualTo(FailureType.TRANSIENT);
                    assertThat(registry.getUninitializedProviders())
                            .containsEntry("acme:email:acs", PendingState.INIT_FAILED);
                });
    }

    @Test
    void lazy_onceTheSettingsExist_theNextLookupBuildsTheAcsProvider() {
        runner.withPropertyValues("notification.providers.lazy-init=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProviderRegistry registry = context.getBean(ProviderRegistry.class);
                    assertThat(registry.getProvider("acme", Channel.EMAIL, null))
                            .isNotInstanceOf(AcsEmailProvider.class);

                    // Stands in for settings arriving later, for example a refreshed property source.
                    context.getBean(NotificationProperties.class).getTenants().get("acme")
                            .getChannels().get("email").getProviders().get("acs").getProperties()
                            .putAll(Map.of(
                                    "connection-string", "endpoint=https://acs.example.com/;accesskey=c2VjcmV0",
                                    "sender", "noreply@example.com"));

                    assertThat(registry.getProvider("acme", Channel.EMAIL, null))
                            .isInstanceOfSatisfying(AcsEmailProvider.class,
                                    provider -> assertThat(provider.isHealthy()).isTrue());
                    assertThat(registry.getAllProviders()).containsOnlyKeys("acme:email:acs");
                    assertThat(registry.getUninitializedProviders()).isEmpty();
                });
    }

    private static NotificationRequest request(String requestId) {
        return NotificationRequest.builder()
                .requestId(requestId)
                .tenantId("acme")
                .notificationType("DELIVERY_EVENT_TEST")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .build();
    }
}
