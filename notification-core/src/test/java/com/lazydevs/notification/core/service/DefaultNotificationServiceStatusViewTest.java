package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.NotificationView;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.util.MessageScrubber;
import com.lazydevs.notification.core.audit.InMemoryNotificationAuditStore;
import com.lazydevs.notification.core.audit.StoreBackedAuditService;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.delivery.InMemoryDeliveryEventStore;
import com.lazydevs.notification.core.idempotency.CaffeineIdempotencyStore;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DD-33: {@link DefaultNotificationService#find} and
 * {@link DefaultNotificationService#findByIdempotencyKey} across a send's
 * lifecycle, over the in-memory audit and delivery event stores.
 */
@ExtendWith(MockitoExtension.class)
class DefaultNotificationServiceStatusViewTest {

    @Mock ProviderRegistry providerRegistry;
    @Mock NotificationTemplateEngine templateEngine;
    @Mock NotificationProvider provider;

    private NotificationProperties properties;
    private CaffeineIdempotencyStore idempotency;
    private InMemoryDeliveryEventStore deliveryEvents;
    private StoreBackedAuditService audit;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.getDeliveryEvents().setEnabled(true);
        idempotency = new CaffeineIdempotencyStore(properties);
        deliveryEvents = new InMemoryDeliveryEventStore(properties);
        audit = new StoreBackedAuditService(
                new InMemoryNotificationAuditStore(100, Duration.ofDays(1), Clock.systemUTC()),
                "default", Clock.systemUTC());
        lenient().when(templateEngine.render(any())).thenReturn(new RenderedContent("s", "b", null, "WELCOME"));
        lenient().when(providerRegistry.getProvider(anyString(), any(Channel.class), any())).thenReturn(provider);
        lenient().when(provider.getProviderName()).thenReturn("ses");
    }

    @Test
    void processing_sent_delivered() {
        DefaultNotificationService service = service(Clock.systemUTC());
        AtomicReference<NotificationView> during = new AtomicReference<>();
        when(provider.send(any(), any())).thenAnswer(inv -> {
            during.set(service.find("acme", "r-1").orElseThrow());
            return SendResult.success("m-1");
        });

        service.send(request("r-1", "k-1"));

        assertThat(during.get().status()).isEqualTo(NotificationStatus.PROCESSING);
        NotificationView sent = service.find("acme", "r-1").orElseThrow();
        assertThat(sent.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(sent.providerMessageId()).isEqualTo("m-1");
        assertThat(sent.provider()).isEqualTo("ses");
        assertThat(sent.sentAt()).isNotNull();
        assertThat(sent.updatedAt()).isNotNull();
        assertThat(sent.recipientCount()).isEqualTo(2);
        assertThat(sent.metadata()).containsExactly(Map.entry("origin", "test"));
        assertThat(sent.deliveryStatus()).isNull();
        assertThat(sent.failureType()).isNull();

        Instant older = Instant.now().minusSeconds(30);
        deliveryEvents.add(new DeliveryEvent(older, "ses", "m-1", "e-0", DeliveryStatus.UNKNOWN, null, null));
        deliveryEvents.add(new DeliveryEvent(Instant.now(), "ses", "m-1", "e-1", DeliveryStatus.DELIVERED, null,
                null));
        NotificationView delivered = service.find("acme", "r-1").orElseThrow();
        assertThat(delivered.deliveryStatus()).isEqualTo("DELIVERED");
        assertThat(delivered.deliveredAt()).isEqualTo(delivered.deliveryStatusAt()).isAfter(older);
    }

    @Test
    void bounceIsShownButIsNotADelivery() {
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-2"));
        DefaultNotificationService service = service(Clock.systemUTC());
        service.send(request("r-2", null));
        deliveryEvents.add(new DeliveryEvent(Instant.now(), "ses", "m-2", "e-2", DeliveryStatus.BOUNCED, "x", null));

        NotificationView view = service.find("acme", "r-2").orElseThrow();
        assertThat(view.deliveryStatus()).isEqualTo("BOUNCED");
        assertThat(view.deliveredAt()).isNull();
    }

    @Test
    void readBeforeDelivered_neverMovesBackwards_andReadCountsAsDelivered() {
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-3"));
        DefaultNotificationService service = service(Clock.systemUTC());
        service.send(request("r-3", null));
        Instant sent = Instant.parse("2026-11-26T10:00:00Z");
        Instant read = sent.plusSeconds(5);
        deliveryEvents.add(new DeliveryEvent(sent, "ses", "m-3", "e-1", DeliveryStatus.SENT, null, null));
        deliveryEvents.add(new DeliveryEvent(read, "ses", "m-3", "e-2", DeliveryStatus.READ, null, null));

        NotificationView readOnly = service.find("acme", "r-3").orElseThrow();
        assertThat(readOnly.deliveryStatus()).isEqualTo("READ");
        assertThat(readOnly.deliveredAt()).isEqualTo(read);

        // The delivered callback arrives late, and a duplicate SENT is stamped even later
        deliveryEvents.add(new DeliveryEvent(sent.plusSeconds(2), "ses", "m-3", "e-3", DeliveryStatus.DELIVERED,
                null, null));
        deliveryEvents.add(new DeliveryEvent(read.plusSeconds(10), "ses", "m-3", "e-4", DeliveryStatus.SENT, null,
                null));
        NotificationView view = service.find("acme", "r-3").orElseThrow();
        assertThat(view.deliveryStatus()).isEqualTo("READ");
        assertThat(view.deliveryStatusAt()).isEqualTo(read);
        assertThat(view.deliveredAt()).isEqualTo(sent.plusSeconds(2));
    }

    @Test
    void sentAndDeliveredInTheSameSecond_showsDelivered() {
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-4"));
        DefaultNotificationService service = service(Clock.systemUTC());
        service.send(request("r-4", null));
        Instant second = Instant.parse("2026-11-26T10:00:00Z");
        deliveryEvents.add(new DeliveryEvent(second, "ses", "m-4", "e-1", DeliveryStatus.DELIVERED, null, null));
        deliveryEvents.add(new DeliveryEvent(second, "ses", "m-4", "e-2", DeliveryStatus.SENT, null, null));

        NotificationView view = service.find("acme", "r-4").orElseThrow();
        assertThat(view.deliveryStatus()).isEqualTo("DELIVERED");
        assertThat(view.deliveredAt()).isEqualTo(second);
    }

    @Test
    void aLaterFailureAfterSent_isShown() {
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-5"));
        DefaultNotificationService service = service(Clock.systemUTC());
        service.send(request("r-5", null));
        Instant sent = Instant.parse("2026-11-26T10:00:00Z");
        deliveryEvents.add(new DeliveryEvent(sent, "ses", "m-5", "e-1", DeliveryStatus.SENT, null, null));
        deliveryEvents.add(new DeliveryEvent(sent.plusSeconds(1), "ses", "m-5", "e-2",
                DeliveryStatus.FAILED_AT_PROVIDER, "x", null));

        NotificationView view = service.find("acme", "r-5").orElseThrow();
        assertThat(view.deliveryStatus()).isEqualTo("FAILED_AT_PROVIDER");
        assertThat(view.deliveredAt()).isNull();
    }

    @Test
    void permanentFailure_isClassifiedAndScrubbed() {
        when(provider.send(any(), any())).thenReturn(SendResult.failure("SES_REJECT",
                "Address jane.doe@example.com suppressed", FailureType.PERMANENT));
        DefaultNotificationService service = service(Clock.systemUTC());

        service.send(request("r-3", "k-3"));

        NotificationView view = service.find("acme", "r-3").orElseThrow();
        assertThat(view.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(view.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(view.errorCode()).isEqualTo("SES_REJECT");
        assertThat(view.errorMessage()).doesNotContain("jane.doe");
        assertThat(view.failedAt()).isNotNull();
        assertThat(view.deliveryStatus()).isNull();
    }

    @Test
    void inProgressTimeout_settlesTheOriginalRecordAsAmbiguous() {
        properties.getIdempotency().setInProgressTimeout(Duration.ofMinutes(1));
        NotificationRequest stuck = request("r-stuck", "k-stuck");
        idempotency.markInProgress(new IdempotencyKey("acme", "billing", "k-stuck"), "r-stuck", "fp");
        audit.recordReceived(stuck);
        DefaultNotificationService later = service(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(2)));

        later.send(request("r-retry", "k-stuck"));

        NotificationView view = later.findByIdempotencyKey("acme", "billing", "k-stuck").orElseThrow();
        assertThat(view.requestId()).isEqualTo("r-stuck");
        assertThat(view.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(view.failureType()).isEqualTo(FailureType.AMBIGUOUS);
        assertThat(view.errorCode()).isEqualTo("IN_PROGRESS_TIMEOUT");
        assertThat(later.find("acme", "r-retry")).isEmpty();
    }

    @Test
    void replay_keepsTheOriginalView() {
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-4"));
        DefaultNotificationService service = service(Clock.systemUTC());
        service.send(request("r-4", "k-4"));

        NotificationResponse replay = service.send(request("r-4b", "k-4"));

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(service.find("acme", "r-4b")).isEmpty();
        assertThat(service.findByIdempotencyKey("acme", "billing", "k-4").orElseThrow().requestId())
                .isEqualTo("r-4");
        assertThat(service.findByIdempotencyKey("acme", " ", "k-4")).isEmpty();
    }

    @Test
    void blankTenantMeansTheDefaultTenant_andBlankIdsFindNothing() {
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-5"));
        DefaultNotificationService service = service(Clock.systemUTC());
        NotificationRequest request = request("r-5", "k-5");
        request.setTenantId(null);
        service.send(request);

        assertThat(service.find(null, "r-5")).isPresent();
        assertThat(service.find(" ", "r-5").orElseThrow().tenantId()).isEqualTo("default");
        assertThat(service.findByIdempotencyKey(null, "billing", "k-5")).isPresent();
        assertThat(service.find("default", " ")).isEmpty();
        assertThat(service.find("default", null)).isEmpty();
        assertThat(service.findByIdempotencyKey("default", "billing", "")).isEmpty();
    }

    @Test
    void failingDeliveryEventStore_stillAnswersTheView() {
        DeliveryEventStore broken = mock(DeliveryEventStore.class);
        when(broken.findByProviderMessageId(any(), any())).thenThrow(new IllegalStateException("down"));
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-6"));
        DefaultNotificationService service = new DefaultNotificationService(properties, providerRegistry,
                templateEngine, audit, Optional.of(idempotency), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(),
                new NotificationServiceExtensions(MessageScrubber.placeholders(), Clock.systemUTC(), broken, null, null));
        service.send(request("r-6", null));

        assertThat(service.find("acme", "r-6").orElseThrow().deliveryStatus()).isNull();
    }

    @Test
    void auditOff_findsNothing_andTheInterfaceDefaultsAreEmpty() {
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-7"));
        DefaultNotificationService service = new DefaultNotificationService(properties, providerRegistry,
                templateEngine, new NoOpAuditService(), Optional.of(idempotency), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
        service.send(request("r-7", "k-7"));

        assertThat(service.find("acme", "r-7")).isEmpty();
        assertThat(service.findByIdempotencyKey("acme", "billing", "k-7")).isEmpty();
        NotificationService minimal = mock(NotificationService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        assertThat(minimal.find("t", "r")).isEmpty();
        assertThat(minimal.findByIdempotencyKey("t", "c", "k")).isEmpty();
    }

    private DefaultNotificationService service(Clock clock) {
        return new DefaultNotificationService(properties, providerRegistry, templateEngine, audit,
                Optional.of(idempotency), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                new NotificationServiceExtensions(MessageScrubber.placeholders(), clock, deliveryEvents, null, null));
    }

    private static NotificationRequest request(String requestId, String key) {
        return NotificationRequest.builder()
                .requestId(requestId)
                .tenantId("acme")
                .callerId("billing")
                .notificationType("WELCOME")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", List.of("cc@example.com"), null, null, null))
                .metadata(Map.of("origin", "test"))
                .idempotencyKey(key)
                .build();
    }
}
