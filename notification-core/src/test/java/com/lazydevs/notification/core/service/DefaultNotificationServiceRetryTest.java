package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.api.ratelimit.RateLimiter.Decision;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.retry.RetryExecutor;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Service-level tests for the DD-13 retry + DLQ integration.
 *
 * <p>These tests use a real {@link RetryExecutor} (configured for tight
 * timing) so the retry-loop semantics are end-to-end exercised through
 * the service. The deadLetterStore and rateLimiter are mocked so
 * assertions can be precise about what got pushed where.
 */
@ExtendWith(MockitoExtension.class)
class DefaultNotificationServiceRetryTest {

    @Mock NotificationProperties properties;
    @Mock ProviderRegistry providerRegistry;
    @Mock NotificationTemplateEngine templateEngine;
    @Mock NotificationAuditService auditService;
    @Mock IdempotencyStore idempotencyStore;
    @Mock RateLimiter rateLimiter;
    @Mock NotificationProvider provider;
    @Mock DeadLetterStore deadLetterStore;

    private DefaultNotificationService service;
    private RetryExecutor retryExecutor;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getDefaultTenant()).thenReturn("default");

        // Real retry config — tight delays so tests finish fast.
        NotificationProperties realProps = new NotificationProperties();
        realProps.getRetry().setEnabled(true);
        realProps.getRetry().setMaxAttempts(3);
        realProps.getRetry().setInitialDelay(Duration.ofMillis(1));
        realProps.getRetry().setMultiplier(2.0);
        realProps.getRetry().setMaxDelay(Duration.ofMillis(5));
        realProps.getRetry().setJitter(0.0);
        retryExecutor = new RetryExecutor(realProps, Optional.empty());

        service = new DefaultNotificationService(
                properties, providerRegistry, templateEngine, auditService,
                Optional.empty(),                // idempotency disabled
                Optional.empty(),                // rate limit disabled
                Optional.of(retryExecutor),
                Optional.of(deadLetterStore));
    }

    @Test
    void transientFailureThenSuccess_doesNotRecordToDLQ() {
        AtomicInteger calls = new AtomicInteger();
        stubRender();
        stubProviderResolution();
        when(provider.send(any(), any())).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                return SendResult.failure("THROTTLED", "rate limited", FailureType.TRANSIENT);
            }
            return SendResult.success("msg-recover");
        });

        NotificationResponse response = service.send(baseRequest());

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(calls.get()).isEqualTo(2);
        // Successful retry path → nothing in DLQ.
        verifyNoInteractions(deadLetterStore);
    }

    @Test
    void permanentFailure_recordedToDLQ_withAttemptsOne() {
        stubRender();
        stubProviderResolution();
        when(provider.send(any(), any()))
                .thenReturn(SendResult.failure("BAD_RECIPIENT", "Invalid email",
                        FailureType.PERMANENT));

        NotificationResponse response = service.send(baseRequest());

        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
        // Permanent failure → no retries, DLQ records 1 attempt with PERMANENT.
        verify(provider, times(1)).send(any(), any());
        ArgumentCaptor<DeadLetterEntry> captor = ArgumentCaptor.forClass(DeadLetterEntry.class);
        verify(deadLetterStore).record(captor.capture());
        assertThat(captor.getValue().attempts()).isEqualTo(1);
        assertThat(captor.getValue().failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(captor.getValue().response().errorCode()).isEqualTo("BAD_RECIPIENT");
    }

    @Test
    void exhaustedRetries_recordedToDLQ_withMaxAttempts() {
        stubRender();
        stubProviderResolution();
        when(provider.send(any(), any()))
                .thenReturn(SendResult.failure("THROTTLED", "still throttled",
                        FailureType.TRANSIENT));

        NotificationResponse response = service.send(baseRequest());

        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
        // 3 max-attempts × 1 send each = 3 calls, then DLQ.
        verify(provider, times(3)).send(any(), any());
        ArgumentCaptor<DeadLetterEntry> captor = ArgumentCaptor.forClass(DeadLetterEntry.class);
        verify(deadLetterStore).record(captor.capture());
        assertThat(captor.getValue().attempts()).isEqualTo(3);
        assertThat(captor.getValue().failureType()).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void unknownFailure_isRetriedByDefaultPredicate() {
        // Backwards-compat: SendResult.failure(code, msg) without a
        // FailureType defaults to UNKNOWN, which the default predicate
        // retries. Ensures pre-DD-13 providers continue to benefit.
        stubRender();
        stubProviderResolution();
        AtomicInteger calls = new AtomicInteger();
        when(provider.send(any(), any())).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            if (n < 3) {
                return SendResult.failure("MYSTERY", "no idea");  // → UNKNOWN
            }
            return SendResult.success("msg-eventually");
        });

        NotificationResponse response = service.send(baseRequest());

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(calls.get()).isEqualTo(3);
        verifyNoInteractions(deadLetterStore);
    }

    @Test
    void rateLimitNotConsumedPerRetry() {
        // Build a service with rate limit AND retry configured. Single
        // provider failure that retries should consume exactly one
        // token, not three (DD-13 §"Why a single retry-budget for the
        // whole send").
        DefaultNotificationService serviceWithLimit = new DefaultNotificationService(
                properties, providerRegistry, templateEngine, auditService,
                Optional.empty(),
                Optional.of(rateLimiter),
                Optional.of(retryExecutor),
                Optional.of(deadLetterStore));

        when(rateLimiter.tryConsume(any())).thenReturn(Decision.allow());
        stubRender();
        stubProviderResolution();
        AtomicInteger calls = new AtomicInteger();
        when(provider.send(any(), any())).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            if (n < 2) {
                return SendResult.failure("THROTTLED", "transient", FailureType.TRANSIENT);
            }
            return SendResult.success("msg-2");
        });

        serviceWithLimit.send(baseRequest());

        // Provider called twice (1 transient + 1 success) but rate
        // limiter was consulted exactly once.
        verify(provider, times(2)).send(any(), any());
        verify(rateLimiter, times(1)).tryConsume(any());
    }

    @Test
    void retryDisabled_singleAttempt_failureStillGoesToDLQ() {
        // Service with DLQ but no RetryExecutor — single attempt,
        // failure still recorded so operators don't lose visibility
        // when they enable DLQ before retry.
        DefaultNotificationService noRetry = new DefaultNotificationService(
                properties, providerRegistry, templateEngine, auditService,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),                // no retry
                Optional.of(deadLetterStore));

        stubRender();
        stubProviderResolution();
        when(provider.send(any(), any()))
                .thenReturn(SendResult.failure("BOOM", "first try fail"));

        noRetry.send(baseRequest());

        verify(provider, times(1)).send(any(), any());
        verify(deadLetterStore).record(any(DeadLetterEntry.class));
    }

    @Test
    void successPath_doesNotTouchDLQ() {
        stubRender();
        stubProviderResolution();
        when(provider.send(any(), any())).thenReturn(SendResult.success("msg-1"));

        service.send(baseRequest());

        verifyNoInteractions(deadLetterStore);
    }

    @Test
    void deadLetterStoreThrows_doesNotPropagate() {
        // SPI contract: implementations should never throw. Defensive
        // wrapping in the service should prevent any escape.
        stubRender();
        stubProviderResolution();
        when(provider.send(any(), any()))
                .thenReturn(SendResult.failure("X", "x", FailureType.PERMANENT));
        org.mockito.Mockito.doThrow(new RuntimeException("DLQ broke"))
                .when(deadLetterStore).record(any());

        // Send must still complete with a FAILED response — DLQ failure
        // is a non-event from the caller's perspective.
        NotificationResponse response = service.send(baseRequest());
        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
        verify(deadLetterStore, atLeastOnce()).record(any());
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private void stubRender() {
        when(templateEngine.render(any())).thenReturn(
                new RenderedContent("subj", "body", null, "TEST"));
    }

    private void stubProviderResolution() {
        when(providerRegistry.getProvider(anyString(), any(Channel.class), any()))
                .thenReturn(provider);
        lenient().when(provider.getProviderName()).thenReturn("smtp");
    }

    private static NotificationRequest baseRequest() {
        return NotificationRequest.builder()
                .requestId("req-test-" + System.nanoTime())
                .tenantId("acme")
                .callerId("billing-svc")
                .notificationType("TEST")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .build();
    }
}
