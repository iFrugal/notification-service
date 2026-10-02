package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.NotificationException;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.idempotency.CaffeineIdempotencyStore;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.retry.RetryExecutor;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provider error text can quote the recipient. It is redacted before it
 * reaches the response, the audit record and the idempotency store, and the
 * stored copy of a failed response carries no error text at all.
 */
@ExtendWith(MockitoExtension.class)
class DefaultNotificationServiceRedactionTest {

    private static final String ADDRESS_TEXT = "Recipient john.doe@example.com (+15551234590) is suppressed";

    @Mock ProviderRegistry providerRegistry;
    @Mock NotificationTemplateEngine templateEngine;
    @Mock NotificationAuditService auditService;
    @Mock NotificationProvider provider;

    private NotificationProperties properties;
    private CaffeineIdempotencyStore store;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        // Keep failed records so the stored copy can be inspected.
        properties.getIdempotency().setRetryAfterFailure(false);
        store = new CaffeineIdempotencyStore(properties);
        lenient().when(templateEngine.render(any())).thenReturn(
                new RenderedContent("subj", "body", null, "ORDER_CONFIRMATION"));
        lenient().when(providerRegistry.getProvider(anyString(), any(Channel.class), any())).thenReturn(provider);
        lenient().when(provider.getProviderName()).thenReturn("custom");
    }

    @Test
    void customProviderFailureText_isRedactedInResponseAndAudit_andNotStored() {
        when(provider.send(any(), any()))
                .thenReturn(SendResult.failure("ACS_400", ADDRESS_TEXT, FailureType.PERMANENT));

        NotificationResponse response = service(Optional.empty()).send(request("idem-redact"));

        assertRedacted(response, "ACS_400");
        ArgumentCaptor<String> audited = ArgumentCaptor.forClass(String.class);
        verify(auditService).updateStatus(eq(response.requestId()), eq(NotificationStatus.FAILED),
                any(), eq("ACS_400"), audited.capture());
        assertThat(audited.getValue()).isEqualTo(response.errorMessage());
        IdempotencyRecord stored = store.findExisting(new IdempotencyKey("acme", null, "idem-redact")).orElseThrow();
        assertThat(stored.response().errorMessage()).isNull();
        assertThat(stored.response().errorCode()).isEqualTo("ACS_400");
        assertThat(stored.response().status()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void providerThrowingThroughTheRetryExecutor_isRedacted() {
        properties.getRetry().setMaxAttempts(1);
        when(provider.send(any(), any())).thenThrow(new IllegalStateException(ADDRESS_TEXT));

        NotificationResponse response = service(Optional.of(new RetryExecutor(properties, Optional.empty())))
                .send(request("idem-retry"));

        assertRedacted(response, "IllegalStateException");
    }

    @Test
    void providerThrowingWithoutRetry_isRedacted() {
        when(provider.send(any(), any())).thenThrow(new IllegalStateException(ADDRESS_TEXT));

        NotificationResponse response = service(Optional.empty()).send(request("idem-throw"));

        assertRedacted(response, "INTERNAL_ERROR");
    }

    @Test
    void notificationExceptionText_isRedacted() {
        when(templateEngine.render(any())).thenThrow(new NotificationException("TEMPLATE_ERROR", ADDRESS_TEXT));

        NotificationResponse response = service(Optional.empty()).send(request("idem-template"));

        assertRedacted(response, "TEMPLATE_ERROR");
    }

    private static void assertRedacted(NotificationResponse response, String errorCode) {
        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(response.errorCode()).isEqualTo(errorCode);
        assertThat(response.errorMessage())
                .doesNotContain("john.doe@example.com")
                .doesNotContain("15551234590")
                .contains("j***@example.com")
                .contains("is suppressed");
    }

    private DefaultNotificationService service(Optional<RetryExecutor> retryExecutor) {
        return new DefaultNotificationService(properties, providerRegistry, templateEngine, auditService,
                Optional.of(store), Optional.empty(), retryExecutor, Optional.empty(), Optional.empty());
    }

    private static NotificationRequest request(String idempotencyKey) {
        return NotificationRequest.builder()
                .requestId("req-" + System.nanoTime())
                .tenantId("acme")
                .notificationType("ORDER_CONFIRMATION")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "john.doe@example.com", null, null, null, null))
                .idempotencyKey(idempotencyKey)
                .build();
    }
}
