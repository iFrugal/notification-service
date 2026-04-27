package com.lazydevs.notification.kafka;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.core.config.NotificationProperties;
import lazydevs.persistence.connection.multitenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotificationKafkaListener}'s tenant + caller-id
 * header propagation.
 *
 * <p>Calls {@code handleNotification(...)} directly — exercising the full
 * Kafka container would need spring-kafka-test's {@code @EmbeddedKafka},
 * which is heavyweight for a contract test like this. The annotation-based
 * arg binding is exercised at runtime via Spring; here we focus on the
 * business logic the listener applies on top of those args.
 */
@ExtendWith(MockitoExtension.class)
class NotificationKafkaListenerTest {

    @Mock NotificationService notificationService;

    private NotificationProperties properties;
    private NotificationKafkaListener listener;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.setDefaultTenant("default");
        properties.getKafka().setEnabled(true);
        listener = new NotificationKafkaListener(notificationService, properties);
        // The notificationService.send() return value is irrelevant for
        // most assertions — we care about what's stamped *before* the call.
        // lenient() because not every test triggers send().
        //
        // Null-guard the dereference: when later tests call
        // when(...).thenSomething() to re-stub, Mockito invokes this Answer
        // during stubbing registration with a phantom (null) argument. If
        // we eagerly read the request the test fails with an NPE before
        // it even runs.
        lenient().when(notificationService.send(any())).thenAnswer(inv -> {
            NotificationRequest captured = inv.getArgument(0, NotificationRequest.class);
            return captured == null ? null : dummyResponse(captured);
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.reset();
    }

    @Test
    void bothHeadersPresent_propagateToContextAndRequest() {
        NotificationRequest req = baseRequest();

        listener.handleNotification(req, "acme", "billing-svc",
                "key-1", 0, 42L);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).send(captor.capture());
        // The request the service sees has callerId set to the header value.
        assertThat(captor.getValue().getCallerId()).isEqualTo("billing-svc");
    }

    @Test
    void bodyCallerId_winsOverHeader() {
        // DD-11 §request-precedence: if the body sets callerId, header is ignored.
        NotificationRequest req = baseRequest();
        req.setCallerId("from-body");

        listener.handleNotification(req, "acme", "from-header",
                "key-2", 0, 43L);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).send(captor.capture());
        assertThat(captor.getValue().getCallerId()).isEqualTo("from-body");
    }

    @Test
    void callerHeaderAbsent_callerIdRemainsNull() {
        // Pre-DD-11 behaviour preserved when no caller header present.
        NotificationRequest req = baseRequest();

        listener.handleNotification(req, "acme", null,
                "key-3", 0, 44L);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).send(captor.capture());
        assertThat(captor.getValue().getCallerId()).isNull();
    }

    @Test
    void callerHeaderBlank_treatedAsAbsent() {
        NotificationRequest req = baseRequest();

        listener.handleNotification(req, "acme", "   ",
                "key-4", 0, 45L);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).send(captor.capture());
        assertThat(captor.getValue().getCallerId())
                .as("Whitespace-only caller header should not be stamped onto the request")
                .isNull();
    }

    @Test
    void tenantHeaderAbsent_fallsBackToDefault() {
        NotificationRequest req = baseRequest();

        // We can't observe TenantContext from inside the mocked send(),
        // so we use a stub Answer that captures the live tenant id.
        String[] capturedTenant = new String[1];
        when(notificationService.send(any())).thenAnswer(inv -> {
            capturedTenant[0] = TenantContext.getTenantId();
            return dummyResponse(inv.getArgument(0, NotificationRequest.class));
        });

        listener.handleNotification(req, null, null, "key-5", 0, 46L);

        assertThat(capturedTenant[0]).isEqualTo("default");
    }

    @Test
    void serviceException_doesNotRethrow_andStillResetsTenantContext() {
        // The listener intentionally swallows exceptions so Kafka commits
        // the offset (failed notifications get persisted via audit) — but
        // we still need to assert that the TenantContext is cleared
        // afterwards so the next message on the same thread isn't tainted.
        NotificationRequest req = baseRequest();
        when(notificationService.send(any())).thenThrow(new RuntimeException("provider down"));

        listener.handleNotification(req, "acme", "billing-svc", "key-6", 0, 47L);

        // No throw escaped, and the tenant context is reset in the finally block.
        assertThat(TenantContext.getTenantId()).isNull();
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private static NotificationRequest baseRequest() {
        return NotificationRequest.builder()
                .requestId("req-test")
                .notificationType("ORDER_CONFIRMATION")
                .channel(Channel.EMAIL)
                .build();
    }

    private static NotificationResponse dummyResponse(NotificationRequest req) {
        return new NotificationResponse(
                req.getRequestId(),
                req.getCorrelationId(),
                req.getTenantId(),
                req.getCallerId(),
                req.getChannel(),
                "smtp",
                NotificationStatus.SENT,
                "msg-id",
                null, null,
                Instant.now(), Instant.now(), Instant.now(),
                null);
    }
}
