package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.PushRecipient;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.push.PushDeviceRegistration;
import com.lazydevs.notification.api.push.PushPlatform;
import com.lazydevs.notification.api.push.PushTargeting;
import com.lazydevs.notification.api.util.MessageScrubber;
import com.lazydevs.notification.core.audit.InMemoryNotificationAuditStore;
import com.lazydevs.notification.core.audit.StoreBackedAuditService;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.idempotency.CaffeineIdempotencyStore;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.push.InMemoryPushDeviceRegistry;
import com.lazydevs.notification.core.push.PushTargetExpansion;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DD-37: a push recipient that names a user, through {@link DefaultNotificationService#send}.
 */
@ExtendWith(MockitoExtension.class)
class DefaultNotificationServicePushTargetTest {

    @Mock ProviderRegistry providerRegistry;
    @Mock NotificationTemplateEngine templateEngine;
    @Mock NotificationProvider provider;
    @Mock DeadLetterStore deadLetterStore;

    private NotificationProperties properties;
    private CaffeineIdempotencyStore idempotency;
    private StoreBackedAuditService audit;
    private InMemoryPushDeviceRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        idempotency = new CaffeineIdempotencyStore(properties);
        audit = new StoreBackedAuditService(
                new InMemoryNotificationAuditStore(100, Duration.ofDays(1), Clock.systemUTC()), "default",
                Clock.systemUTC());
        registry = new InMemoryPushDeviceRegistry(10, Duration.ofDays(60), Clock.systemUTC());
        lenient().when(templateEngine.render(any())).thenReturn(RenderedContent.text("You have a match"));
        lenient().when(providerRegistry.getProvider(anyString(), any(Channel.class), any())).thenReturn(provider);
        lenient().when(provider.getProviderName()).thenReturn("fcm");
    }

    private DefaultNotificationService service(PushTargetExpansion expansion) {
        return new DefaultNotificationService(properties, providerRegistry, templateEngine, audit,
                Optional.of(idempotency), Optional.empty(), Optional.empty(), Optional.of(deadLetterStore),
                Optional.empty(),
                new NotificationServiceExtensions(MessageScrubber.placeholders(), Clock.systemUTC(), null, null, null,
                        null, expansion));
    }

    private static NotificationRequest request(PushRecipient recipient, Map<String, String> metadata) {
        return NotificationRequest.builder()
                .tenantId("acme")
                .idempotencyKey("k-1")
                .notificationType("MATCH")
                .channel(Channel.PUSH)
                .recipient(recipient)
                .metadata(metadata)
                .build();
    }

    private void registerDevices(String user, String... tokens) {
        for (int i = 0; i < tokens.length; i++) {
            registry.register(new PushDeviceRegistration(null, user, "inst-" + i, null, PushPlatform.ANDROID,
                    tokens[i], null, null, null));
        }
    }

    @Test
    void aUserTarget_goesToTheUsersDevices_andOnlyTheProviderSeesTheTokens() {
        registerDevices("u1", "t1", "t2");
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-1"));
        NotificationRequest request = request(PushRecipient.forUser("u1"), null);

        NotificationResponse response = service(new PushTargetExpansion(registry, "any")).send(request);

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(provider).send(sent.capture(), any());
        PushRecipient sentTo = (PushRecipient) sent.getValue().getRecipient();
        assertThat(sentTo.userId()).isNull();
        assertThat(sentTo.deviceTokens()).containsExactlyInAnyOrder("t1", "t2");
        assertThat(sent.getValue().getMetadata()).containsEntry(PushTargeting.MULTI_TOKEN_POLICY_METADATA, "any");
        // The caller's request, which idempotency and audit keep, still names the user.
        assertThat(((PushRecipient) request.getRecipient()).userId()).isEqualTo("u1");
        assertThat(audit.findByRequestId("acme", request.getRequestId()).orElseThrow().getRecipientSummary())
                .startsWith("user=").doesNotContain("t1");
        assertThat(idempotency.findExisting(new IdempotencyKey("acme", null, "k-1")).orElseThrow()
                .response().status()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void theCallersPolicy_wins_overTheConfiguredOne() {
        registerDevices("u1", "t1");
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-1"));

        service(new PushTargetExpansion(registry, "any")).send(request(PushRecipient.forUser("u1"),
                Map.of(PushTargeting.MULTI_TOKEN_POLICY_METADATA, "all")));

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(provider).send(sent.capture(), any());
        assertThat(sent.getValue().getMetadata()).containsEntry(PushTargeting.MULTI_TOKEN_POLICY_METADATA, "all");
    }

    @Test
    void noActiveDevice_isAPermanentFailure_withoutAProviderCallOrADeadLetter() {
        NotificationResponse response = service(new PushTargetExpansion(registry, "any"))
                .send(request(PushRecipient.forUser("u1"), null));

        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(response.errorCode()).isEqualTo(PushTargeting.NO_DEVICES);
        assertThat(response.failureType()).isEqualTo(FailureType.PERMANENT);
        verify(provider, never()).send(any(), any());
        verifyNoInteractions(deadLetterStore);
        // A failed key is released, so the caller may retry once a device registered.
        registerDevices("u1", "t1");
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-1"));
        assertThat(service(new PushTargetExpansion(registry, "any"))
                .send(request(PushRecipient.forUser("u1"), null)).status()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void aProviderFailure_isDeadLettered_withTheUserTarget_notTheTokens() {
        registerDevices("u1", "t1");
        when(provider.send(any(), any())).thenReturn(SendResult.failure("FCM_UNAVAILABLE", "down",
                FailureType.TRANSIENT));

        service(new PushTargetExpansion(registry, "any")).send(request(PushRecipient.forUser("u1"), null));

        ArgumentCaptor<DeadLetterEntry> entry = ArgumentCaptor.forClass(DeadLetterEntry.class);
        verify(deadLetterStore).add(entry.capture());
        PushRecipient stored = (PushRecipient) entry.getValue().request().getRecipient();
        assertThat(stored.userId()).isEqualTo("u1");
        assertThat(stored.deviceTokens()).isNull();
    }

    @Test
    void withoutARegistry_aUserTargetIsRefused_andDeadLettered() {
        NotificationResponse response = service(null).send(request(PushRecipient.forUser("u1"), null));

        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(response.errorCode()).isEqualTo(PushTargeting.USER_TARGETING_DISABLED);
        verify(provider, never()).send(any(), any());
        verify(deadLetterStore).add(any());
    }

    @Test
    void aUserTargetWithAnotherTarget_isRefused() {
        PushRecipient both = new PushRecipient(null, "token", null, null, null, null, null, null, null, null, null,
                null, null, "u1", null);

        NotificationResponse response = service(new PushTargetExpansion(registry, "any")).send(request(both, null));

        assertThat(response.errorCode()).isEqualTo(PushTargeting.INVALID_USER_TARGET);
        verify(provider, never()).send(any(), any());
    }

    @Test
    void aTokenTarget_isUntouched() {
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-1"));
        PushRecipient token = new PushRecipient(null, "token-1", null, null, null, null, null, null, null, null, null);
        NotificationRequest request = request(token, null);

        service(null).send(request);

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(provider).send(sent.capture(), any());
        assertThat(sent.getValue()).isSameAs(request);
        assertThat(List.of(((PushRecipient) sent.getValue().getRecipient()).deviceToken())).containsExactly("token-1");
    }
}
