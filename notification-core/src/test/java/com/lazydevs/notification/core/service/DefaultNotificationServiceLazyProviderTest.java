package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.EmailProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.ChannelConfig;
import com.lazydevs.notification.core.config.NotificationProperties.ProviderConfig;
import com.lazydevs.notification.core.config.NotificationProperties.TenantConfig;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.provider.ProviderResolver;
import com.lazydevs.notification.core.retry.RetryExecutor;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service-level proof of DD-29: with {@code notification.providers.lazy-init}
 * a provider that cannot be built turns a send into a FAILED response with
 * {@code PROVIDER_NOT_CONFIGURED}, recorded in the dead-letter store as
 * {@link FailureType#TRANSIENT}; once it can be built the send goes through.
 * Uses a real {@link ProviderRegistry} and an unchanged
 * {@link DefaultNotificationService}.
 */
class DefaultNotificationServiceLazyProviderTest {

    /** Provider whose configure() fails while {@link #endpoint} is absent. */
    static final class EndpointEmailProvider implements EmailProvider {
        @Override
        public String getProviderName() {
            return "fake";
        }

        @Override
        public void configure(Map<String, Object> properties) {
            if (properties.get("endpoint") == null) {
                throw new IllegalArgumentException("endpoint is required");
            }
        }

        @Override
        public SendResult send(NotificationRequest request, RenderedContent content) {
            return SendResult.success("msg-1");
        }
    }

    private NotificationProperties properties;
    private ProviderConfig providerConfig;
    private ProviderRegistry registry;
    private DeadLetterStore deadLetterStore;
    private NotificationTemplateEngine templateEngine;
    private final AtomicInteger builds = new AtomicInteger();

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.getProviders().setLazyInit(true);
        providerConfig = new ProviderConfig();
        ChannelConfig email = new ChannelConfig();
        email.getProviders().put("fake", providerConfig);
        TenantConfig tenant = new TenantConfig();
        tenant.getChannels().put("email", email);
        properties.getTenants().put("acme", tenant);

        ProviderResolver resolver = mock(ProviderResolver.class);
        when(resolver.resolve(anyString(), anyString(), any(Channel.class), any(ProviderConfig.class), any()))
                .thenAnswer(inv -> {
                    builds.incrementAndGet();
                    return new EndpointEmailProvider();
                });
        registry = new ProviderRegistry(properties, resolver);
        registry.initializeProviders();

        deadLetterStore = mock(DeadLetterStore.class);
        templateEngine = mock(NotificationTemplateEngine.class);
        when(templateEngine.render(any())).thenReturn(new RenderedContent("subj", "body", null, "TEST"));
    }

    private DefaultNotificationService service(Optional<RetryExecutor> retryExecutor) {
        return new DefaultNotificationService(properties, registry, templateEngine,
                mock(NotificationAuditService.class), Optional.empty(), Optional.empty(),
                retryExecutor, Optional.of(deadLetterStore), Optional.empty());
    }

    @Test
    void unbuildableProvider_failsTheSendAsTransientProviderNotConfigured() {
        NotificationResponse response = service(Optional.empty()).send(request());

        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(response.errorCode()).isEqualTo(ProviderRegistry.PROVIDER_NOT_CONFIGURED);
        assertThat(response.errorMessage()).contains("endpoint is required");
        ArgumentCaptor<DeadLetterEntry> captor = ArgumentCaptor.forClass(DeadLetterEntry.class);
        verify(deadLetterStore).add(captor.capture());
        assertThat(captor.getValue().failureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(captor.getValue().attempts()).isEqualTo(1);
    }

    @Test
    void retriesTryTheBuildAgain() {
        NotificationProperties retryProps = new NotificationProperties();
        retryProps.getRetry().setEnabled(true);
        retryProps.getRetry().setMaxAttempts(3);
        retryProps.getRetry().setInitialDelay(Duration.ofMillis(1));
        retryProps.getRetry().setMaxDelay(Duration.ofMillis(2));
        retryProps.getRetry().setJitter(0.0);

        NotificationResponse response = service(Optional.of(new RetryExecutor(retryProps, Optional.empty())))
                .send(request());

        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(response.errorCode()).isEqualTo(ProviderRegistry.PROVIDER_NOT_CONFIGURED);
        // One build per attempt: the lookup's, then one per retry.
        assertThat(builds).hasValue(3);
        ArgumentCaptor<DeadLetterEntry> captor = ArgumentCaptor.forClass(DeadLetterEntry.class);
        verify(deadLetterStore).add(captor.capture());
        assertThat(captor.getValue().failureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(captor.getValue().attempts()).isEqualTo(3);
    }

    @Test
    void onceTheSettingsExist_theNextSendBuildsTheProviderAndSends() {
        DefaultNotificationService service = service(Optional.empty());
        assertThat(service.send(request()).status()).isEqualTo(NotificationStatus.FAILED);

        providerConfig.getProperties().put("endpoint", "https://mail.example");
        NotificationResponse response = service.send(request());

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(response.providerMessageId()).isEqualTo("msg-1");
    }

    private static NotificationRequest request() {
        return NotificationRequest.builder()
                .requestId("req-" + System.nanoTime())
                .tenantId("acme")
                .notificationType("TEST")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .build();
    }
}
