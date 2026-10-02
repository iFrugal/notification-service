package com.lazydevs.notification.starter;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventListener;
import com.lazydevs.notification.api.delivery.DeliveryEventPublisher;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.delivery.DeliveryEvents;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.core.delivery.ListenerDeliveryEventPublisher;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-25: a provider that implements {@code DeliveryEventEmitter} receives the
 * application's {@link DeliveryEventPublisher} before it is configured,
 * whichever way it was resolved, and the events it publishes while sending
 * reach the registered listeners and the delivery-event store. An
 * {@code AMBIGUOUS} failure is not retried and lands in the dead-letter store.
 */
class ProviderDeliveryEventsTest {

    private static final String TENANTS = "notification.tenants.";

    private final WebApplicationContextRunner runner = StarterContextRunners.starterRunner();

    @Test
    void publisherIsInjectedBeforeConfigure_onTheBuiltInBeanNameAndFqcnPaths() {
        runner.withUserConfiguration(EmittingProvidersConfiguration.class)
                .withPropertyValues(
                        // Built-in name, satisfied by a bean under the conventional name.
                        TENANTS + "acme.channels.email.providers.smtp.properties.host=smtp.acme.example",
                        TENANTS + "globex.channels.email.providers.custom.bean-name=customEmittingProvider",
                        TENANTS + "initech.channels.email.providers.custom.fqcn="
                                + EmittingStubEmailProvider.class.getName())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DeliveryEventPublisher publisher = context.getBean(DeliveryEventPublisher.class);
                    assertThat(publisher).isInstanceOf(ListenerDeliveryEventPublisher.class);
                    ProviderRegistry registry = context.getBean(ProviderRegistry.class);

                    for (String tenant : List.of("acme", "globex", "initech")) {
                        assertThat(registry.getProvider(tenant, Channel.EMAIL, null))
                                .as("provider of %s", tenant)
                                .isInstanceOfSatisfying(EmittingStubEmailProvider.class, provider -> {
                                    assertThat(provider.publisher()).isSameAs(publisher);
                                    assertThat(provider.publisherSetBeforeConfigure()).isTrue();
                                });
                    }
                });
    }

    @Test
    void aCustomPublisherBean_replacesTheDefault_andIsTheOneInjected() {
        runner.withUserConfiguration(EmittingProvidersConfiguration.class, CustomPublisherConfiguration.class)
                .withPropertyValues(TENANTS + "acme.channels.email.providers.custom.bean-name=customEmittingProvider")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ListenerDeliveryEventPublisher.class);
                    EmittingStubEmailProvider provider = (EmittingStubEmailProvider) context
                            .getBean(ProviderRegistry.class).getProvider("acme", Channel.EMAIL, null);
                    assertThat(provider.publisher()).isSameAs(CustomPublisherConfiguration.PUBLISHER);
                });
    }

    @Test
    void anEventPublishedDuringSend_reachesTheListenerAndTheStore_andTheAmbiguousFailureIsDeadLettered() {
        runner.withUserConfiguration(EmittingProvidersConfiguration.class, RecordingListenerConfiguration.class)
                .withPropertyValues(
                        TENANTS + "acme.channels.email.providers.custom.bean-name=customEmittingProvider",
                        "notification.delivery-events.enabled=true",
                        "notification.dead-letter.enabled=true",
                        "notification.retry.enabled=true",
                        "notification.retry.initial-delay=1ms",
                        "notification.retry.max-delay=5ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    NotificationRequest request = NotificationRequest.builder()
                            .requestId("req-ambiguous-1")
                            .tenantId("acme")
                            .callerId("billing")
                            .notificationType("DELIVERY_EVENT_TEST")
                            .channel(Channel.EMAIL)
                            .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                            .build();

                    NotificationResponse response = context.getBean(NotificationService.class).send(request);

                    assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
                    assertThat(response.providerMessageId()).isEqualTo(EmittingStubEmailProvider.MESSAGE_ID);
                    EmittingStubEmailProvider provider = (EmittingStubEmailProvider) context
                            .getBean(ProviderRegistry.class).getProvider("acme", Channel.EMAIL, null);
                    assertThat(provider.sends()).as("AMBIGUOUS is not retried").isEqualTo(1);

                    List<DeliveryEvent> heard = RecordingListenerConfiguration.EVENTS;
                    assertThat(heard).singleElement().satisfies(event -> {
                        assertThat(event.status()).isEqualTo(DeliveryStatus.BOUNCED);
                        assertThat(event.reason()).isEqualTo(DeliveryEvents.REASON_INVALID_TARGET);
                        assertThat(event.providerName()).isEqualTo("emitting-stub");
                    });
                    assertThat(context.getBean(DeliveryEventStore.class)
                            .findByProviderMessageId("emitting-stub", EmittingStubEmailProvider.MESSAGE_ID))
                            .hasValueSatisfying(events -> assertThat(events).containsExactlyElementsOf(heard));

                    DeadLetterEntry dead = context.getBean(DeadLetterStore.class)
                            .findByRequestId("acme", "req-ambiguous-1").orElseThrow();
                    assertThat(dead.failureType()).isEqualTo(FailureType.AMBIGUOUS);
                    assertThat(dead.attempts()).isEqualTo(1);
                    assertThat(dead.response().providerMessageId()).isEqualTo(EmittingStubEmailProvider.MESSAGE_ID);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class EmittingProvidersConfiguration {

        @Bean("smtpEmailProvider")
        @Scope("prototype")
        EmittingStubEmailProvider smtpEmailProvider() {
            return new EmittingStubEmailProvider();
        }

        @Bean
        @Scope("prototype")
        EmittingStubEmailProvider customEmittingProvider() {
            return new EmittingStubEmailProvider();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomPublisherConfiguration {

        static final DeliveryEventPublisher PUBLISHER = event -> {
        };

        @Bean
        DeliveryEventPublisher customDeliveryEventPublisher() {
            return PUBLISHER;
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
