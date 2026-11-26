package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyReleasePolicy;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.api.util.MessageScrubber;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.idempotency.CaffeineIdempotencyStore;
import com.lazydevs.notification.core.idempotency.IdempotencyFailurePolicy;
import com.lazydevs.notification.core.idempotency.RequestFingerprint;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DD-30 / DD-32: the {@link IdempotencyReleasePolicy} decides whether a failed
 * send releases its key, and a released key gets its rate-limit token back
 * with {@code notification.rate-limit.refund-unsent}.
 */
@ExtendWith(MockitoExtension.class)
class DefaultNotificationServiceReleasePolicyTest {

    private static final RateLimiter.RateLimitKey BUCKET = new RateLimiter.RateLimitKey("acme", "billing", "email");

    @Mock ProviderRegistry providerRegistry;
    @Mock NotificationTemplateEngine templateEngine;
    @Mock NotificationAuditService auditService;
    @Mock NotificationProvider provider;
    @Mock RateLimiter rateLimiter;

    private NotificationProperties properties;
    private CaffeineIdempotencyStore store;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        store = new CaffeineIdempotencyStore(properties);
        lenient().when(templateEngine.render(any())).thenReturn(new RenderedContent("subj", "body", null, "WELCOME"));
        lenient().when(providerRegistry.getProvider(anyString(), any(Channel.class), any())).thenReturn(provider);
        lenient().when(provider.getProviderName()).thenReturn("smtp");
        lenient().when(rateLimiter.tryConsume(any())).thenReturn(RateLimiter.Decision.allow());
    }

    @Test
    void releaseTransient_transientFailure_refundsTheTokenTheSendConsumed() {
        properties.getIdempotency().setFailurePolicy(IdempotencyFailurePolicy.RELEASE_TRANSIENT);
        properties.getRateLimit().setRefundUnsent(true);
        when(provider.send(any(), any())).thenReturn(SendResult.failure("SMTP_421", "busy", FailureType.TRANSIENT));

        service(null).send(request("k-1"));

        ArgumentCaptor<RateLimiter.RateLimitKey> consumed = ArgumentCaptor.forClass(RateLimiter.RateLimitKey.class);
        verify(rateLimiter).tryConsume(consumed.capture());
        assertThat(consumed.getValue()).isEqualTo(BUCKET);
        verify(rateLimiter).refund(BUCKET);
    }

    @Test
    void refundUnsentOff_releasesButDoesNotRefund() {
        properties.getIdempotency().setFailurePolicy(IdempotencyFailurePolicy.RELEASE_TRANSIENT);
        when(provider.send(any(), any())).thenReturn(SendResult.failure("SMTP_421", "busy", FailureType.TRANSIENT));

        service(null).send(request("k-off"));

        assertThat(store.findExisting(key("k-off"))).isEmpty();
        verify(rateLimiter, never()).refund(any());
    }

    @Test
    void keptFailure_isNotRefunded() {
        properties.getIdempotency().setFailurePolicy(IdempotencyFailurePolicy.RELEASE_TRANSIENT);
        properties.getRateLimit().setRefundUnsent(true);
        when(provider.send(any(), any())).thenReturn(SendResult.failure("E", "bad", FailureType.PERMANENT));

        service(null).send(request("k-kept"));

        assertThat(store.findExisting(key("k-kept"))).isPresent();
        verify(rateLimiter, never()).refund(any());
    }

    @ParameterizedTest
    @EnumSource(value = FailureType.class, names = {"AMBIGUOUS", "UNKNOWN"})
    void releasedButPossiblyDelivered_isNotRefunded(FailureType type) {
        properties.getRateLimit().setRefundUnsent(true);
        when(provider.send(any(), any())).thenReturn(SendResult.failure("E", "timeout", type));

        service(null).send(request("k-maybe-" + type));

        assertThat(store.findExisting(key("k-maybe-" + type))).isEmpty();
        verify(rateLimiter, never()).refund(any());
    }

    @Test
    void releaseThatDidNotHappen_isNotRefunded() {
        properties.getRateLimit().setRefundUnsent(true);
        IdempotencyStore noRelease = spy(store);
        doReturn(false).when(noRelease).release(any(), anyString());
        when(provider.send(any(), any())).thenReturn(SendResult.failure("SMTP_421", "busy", FailureType.TRANSIENT));

        service(noRelease, null).send(request("k-stuck"));

        verify(rateLimiter, never()).refund(any());
    }

    @Test
    void refundError_isSwallowed() {
        properties.getRateLimit().setRefundUnsent(true);
        doThrow(new IllegalStateException("redis down")).when(rateLimiter).refund(any());
        when(provider.send(any(), any())).thenReturn(SendResult.failure("SMTP_421", "busy", FailureType.TRANSIENT));

        NotificationResponse response = service(null).send(request("k-err"));

        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(response.failureType()).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void customPolicy_releasesAPermanentCredentialFailure_andRefundsIt() {
        properties.getIdempotency().setFailurePolicy(IdempotencyFailurePolicy.RELEASE_TRANSIENT);
        properties.getRateLimit().setRefundUnsent(true);
        IdempotencyReleasePolicy policy = (request, failed) ->
                failed.failureType() == FailureType.TRANSIENT || "AUTH_REJECTED".equals(failed.errorCode());
        when(provider.send(any(), any()))
                .thenReturn(SendResult.failure("AUTH_REJECTED", "credential rejected", FailureType.PERMANENT))
                .thenReturn(SendResult.success("m-2"));
        DefaultNotificationService service = service(policy);

        NotificationResponse first = service.send(request("k-cred"));
        NotificationResponse retry = service.send(request("k-cred"));

        assertThat(first.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(retry.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(retry.idempotentReplay()).isNull();
        verify(provider, times(2)).send(any(), any());
        verify(rateLimiter, times(1)).refund(BUCKET);
    }

    @Test
    void customPolicy_keepingAFailure_underReleaseTransient_replaysIt() {
        properties.getIdempotency().setFailurePolicy(IdempotencyFailurePolicy.RELEASE_TRANSIENT);
        when(provider.send(any(), any())).thenReturn(SendResult.failure("SMTP_421", "busy", FailureType.TRANSIENT));
        DefaultNotificationService service = service((request, failed) -> false);

        service.send(request("k-never"));
        NotificationResponse replay = service.send(request("k-never"));

        assertThat(replay.idempotentReplay()).isTrue();
        verify(provider, times(1)).send(any(), any());
    }

    @Test
    void customPolicy_isAskedAgainForALeftoverFailedRecord() {
        IdempotencyKey key = key("k-leftover");
        store.markInProgress(key, "old");
        store.markComplete(key, NotificationResponse.failure(request("k-leftover"), "smtp", "AUTH_REJECTED", null)
                .withFailureType(FailureType.PERMANENT));
        properties.getIdempotency().setFailurePolicy(IdempotencyFailurePolicy.KEEP);
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-1"));

        NotificationResponse response = service((request, failed) -> "AUTH_REJECTED".equals(failed.errorCode()))
                .send(request("k-leftover"));

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void settledInProgressTimeout_isNeverReleased_evenByAPolicyThatReleasesEverything() {
        properties.getIdempotency().setInProgressTimeout(Duration.ofMinutes(5));
        properties.getRateLimit().setRefundUnsent(true);
        IdempotencyKey key = key("k-stale");
        store.markInProgress(key, "stuck", RequestFingerprint.of(request("k-stale")));
        DefaultNotificationService later = new DefaultNotificationService(
                properties, providerRegistry, templateEngine, auditService, Optional.of(store),
                Optional.of(rateLimiter), Optional.empty(), Optional.empty(), Optional.empty(),
                new NotificationServiceExtensions(MessageScrubber.placeholders(),
                        Clock.offset(Clock.systemUTC(), Duration.ofMinutes(6)), null, null, (request, failed) -> true));

        NotificationResponse first = later.send(request("k-stale"));
        NotificationResponse second = later.send(request("k-stale"));

        assertThat(first.errorCode()).isEqualTo(IdempotencyCoordinator.IN_PROGRESS_TIMEOUT);
        assertThat(second.errorCode()).isEqualTo(IdempotencyCoordinator.IN_PROGRESS_TIMEOUT);
        assertThat(second.idempotentReplay()).isTrue();
        verify(provider, never()).send(any(), any());
        verify(rateLimiter, never()).refund(any());
    }

    private DefaultNotificationService service(IdempotencyReleasePolicy policy) {
        return service(store, policy);
    }

    private DefaultNotificationService service(IdempotencyStore idempotencyStore, IdempotencyReleasePolicy policy) {
        return new DefaultNotificationService(
                properties, providerRegistry, templateEngine, auditService, Optional.of(idempotencyStore),
                Optional.of(rateLimiter), Optional.empty(), Optional.empty(), Optional.empty(),
                new NotificationServiceExtensions(MessageScrubber.placeholders(), Clock.systemUTC(), null, null,
                        policy));
    }

    private static IdempotencyKey key(String idempotencyKey) {
        return new IdempotencyKey("acme", "billing", idempotencyKey);
    }

    private static NotificationRequest request(String idempotencyKey) {
        return NotificationRequest.builder()
                .requestId("req-" + System.nanoTime())
                .tenantId("acme")
                .callerId("billing")
                .notificationType("WELCOME")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .templateData(Map.of("name", "Ada"))
                .idempotencyKey(idempotencyKey)
                .build();
    }
}
