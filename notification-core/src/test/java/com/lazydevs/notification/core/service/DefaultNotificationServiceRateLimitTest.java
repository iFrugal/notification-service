package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.RateLimitExceededException;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.idempotency.IdempotencyStatus;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.api.ratelimit.RateLimiter.Decision;
import com.lazydevs.notification.api.ratelimit.RateLimiter.RateLimitKey;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Service-level tests for the DD-12 rate-limit integration into
 * {@link DefaultNotificationService}.
 *
 * <p>The unit-level Bucket4j contract is tested separately in
 * {@code Bucket4jRateLimiterTest}; this suite verifies that the service
 * <em>calls</em> the limiter at the right point in the pipeline and
 * surfaces denials as {@link RateLimitExceededException}.
 */
@ExtendWith(MockitoExtension.class)
class DefaultNotificationServiceRateLimitTest {

    @Mock NotificationProperties properties;
    @Mock ProviderRegistry providerRegistry;
    @Mock NotificationTemplateEngine templateEngine;
    @Mock NotificationAuditService auditService;
    @Mock IdempotencyStore idempotencyStore;
    @Mock NotificationProvider provider;
    @Mock RateLimiter rateLimiter;

    private DefaultNotificationService service;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getDefaultTenant()).thenReturn("default");
        // Default service has idempotency disabled so most tests don't
        // have to stub the store. The replay-exempt test below builds
        // its own service with the store wired.
        service = new DefaultNotificationService(
                properties, providerRegistry, templateEngine, auditService,
                Optional.empty(),
                Optional.of(rateLimiter));
    }

    @Test
    void allowed_proceedsToDispatch() {
        when(rateLimiter.tryConsume(any())).thenReturn(Decision.allow());
        stubProviderHappyPath();

        var response = service.send(baseRequest("billing-svc", Channel.EMAIL));

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        // Limiter consulted exactly once with the (tenant, caller, channel)
        // shape derived from the request.
        ArgumentCaptor<RateLimitKey> captor = ArgumentCaptor.forClass(RateLimitKey.class);
        verify(rateLimiter).tryConsume(captor.capture());
        RateLimitKey k = captor.getValue();
        assertThat(k.tenantId()).isEqualTo("acme");
        assertThat(k.callerId()).isEqualTo("billing-svc");
        assertThat(k.channel()).isEqualTo("email");
    }

    @Test
    void denied_throwsRateLimitExceeded_andSkipsDispatch() {
        when(rateLimiter.tryConsume(any()))
                .thenReturn(Decision.deny(Duration.ofSeconds(7)));

        assertThatThrownBy(() -> service.send(baseRequest("billing-svc", Channel.EMAIL)))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting(t -> ((RateLimitExceededException) t).getRetryAfter())
                .isEqualTo(Duration.ofSeconds(7));

        // No render, no provider lookup, no audit — pre-check short-circuits.
        verifyNoInteractions(providerRegistry, templateEngine);
        verify(auditService, never()).recordReceived(any());
    }

    @Test
    void anonymousCallerId_bucketsUnderLiteralAnonymous() {
        // Missing X-Service-Id: callerId is null. The service substitutes
        // "anonymous" for the bucket key so unidentified traffic shares a
        // bucket rather than each request creating a null-keyed slot.
        when(rateLimiter.tryConsume(any())).thenReturn(Decision.allow());
        stubProviderHappyPath();

        service.send(baseRequest(null, Channel.EMAIL));

        ArgumentCaptor<RateLimitKey> captor = ArgumentCaptor.forClass(RateLimitKey.class);
        verify(rateLimiter).tryConsume(captor.capture());
        assertThat(captor.getValue().callerId()).isEqualTo("anonymous");
    }

    @Test
    void blankCallerId_normalizedToAnonymous() {
        // DD-12 review feedback: "" or "   " should bucket under
        // "anonymous", not under empty/whitespace strings each carving
        // their own slot.
        when(rateLimiter.tryConsume(any())).thenReturn(Decision.allow());
        stubProviderHappyPath();

        NotificationRequest req = baseRequest("billing-svc", Channel.EMAIL);
        req.setCallerId("   ");  // explicitly blank
        service.send(req);

        ArgumentCaptor<RateLimitKey> captor = ArgumentCaptor.forClass(RateLimitKey.class);
        verify(rateLimiter).tryConsume(captor.capture());
        assertThat(captor.getValue().callerId()).isEqualTo("anonymous");
    }

    @Test
    void idempotencyReplay_doesNotConsumeToken() {
        // DD-12 review fix: cached completed replays should bypass the
        // limiter so safe retries don't burn tokens against the caller.
        // Build a service WITH the idempotency store so the replay path
        // is reachable.
        DefaultNotificationService serviceWithIdem = new DefaultNotificationService(
                properties, providerRegistry, templateEngine, auditService,
                Optional.of(idempotencyStore), Optional.of(rateLimiter));

        NotificationRequest req = baseRequest("billing-svc", Channel.EMAIL);
        req.setIdempotencyKey("idem-replay");

        NotificationResponse cached = sentResponse("orig-req");
        IdempotencyRecord rec = new IdempotencyRecord(
                "orig-req", IdempotencyStatus.COMPLETE, cached, java.time.Instant.now());
        when(idempotencyStore.findExisting(any())).thenReturn(Optional.of(rec));

        NotificationResponse response = serviceWithIdem.send(req);

        assertThat(response.idempotentReplay()).isTrue();
        // The crucial assertion: limiter was NOT called for the replay.
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void inProgressConflict_doesNotConsumeToken() {
        // DD-12 §"Why this ordering": IN_PROGRESS conflicts (the cheap
        // 409 path) shouldn't consume a token either — same logic as
        // replays.
        DefaultNotificationService serviceWithIdem = new DefaultNotificationService(
                properties, providerRegistry, templateEngine, auditService,
                Optional.of(idempotencyStore), Optional.of(rateLimiter));

        NotificationRequest req = baseRequest("billing-svc", Channel.EMAIL);
        req.setIdempotencyKey("idem-inflight");

        IdempotencyRecord inFlight = new IdempotencyRecord(
                "winner-req", IdempotencyStatus.IN_PROGRESS, null, java.time.Instant.now());
        when(idempotencyStore.findExisting(any())).thenReturn(Optional.of(inFlight));

        assertThatThrownBy(() -> serviceWithIdem.send(req))
                .isInstanceOf(com.lazydevs.notification.api.exception.IdempotencyInProgressException.class);

        verifyNoInteractions(rateLimiter);
    }

    @Test
    void freshIdempotentSend_consumesToken() {
        // The complement to the two tests above: a brand-new idempotency
        // key (no prior record) DOES go through the limiter, because it's
        // about to do real work (markInProgress + provider call).
        DefaultNotificationService serviceWithIdem = new DefaultNotificationService(
                properties, providerRegistry, templateEngine, auditService,
                Optional.of(idempotencyStore), Optional.of(rateLimiter));

        when(idempotencyStore.findExisting(any())).thenReturn(Optional.empty());
        when(idempotencyStore.markInProgress(any(), anyString())).thenReturn(true);
        when(rateLimiter.tryConsume(any())).thenReturn(Decision.allow());
        stubProviderHappyPath();

        NotificationRequest req = baseRequest("billing-svc", Channel.EMAIL);
        req.setIdempotencyKey("idem-fresh");
        serviceWithIdem.send(req);

        verify(rateLimiter).tryConsume(any());
    }

    @Test
    void rateLimiterAbsent_neverCallsLimiter_proceedsToDispatch() {
        DefaultNotificationService noLimiterService = new DefaultNotificationService(
                properties, providerRegistry, templateEngine, auditService,
                Optional.empty(), Optional.empty());
        stubProviderHappyPath();

        noLimiterService.send(baseRequest("billing-svc", Channel.EMAIL));

        verifyNoInteractions(rateLimiter);
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private void stubProviderHappyPath() {
        when(templateEngine.render(any())).thenReturn(
                new RenderedContent("subj", "body", null, "TEST"));
        when(providerRegistry.getProvider(anyString(), any(Channel.class), any()))
                .thenReturn(provider);
        when(provider.send(any(), any())).thenReturn(SendResult.success("provider-msg"));
        when(provider.getProviderName()).thenReturn("smtp");
    }

    private static NotificationRequest baseRequest(String callerId, Channel channel) {
        return NotificationRequest.builder()
                .requestId("req-test-" + System.nanoTime())
                .tenantId("acme")
                .callerId(callerId)
                .notificationType("TEST")
                .channel(channel)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .build();
    }

    private static NotificationResponse sentResponse(String requestId) {
        return new NotificationResponse(
                requestId, "corr-" + requestId, "acme", "billing-svc",
                Channel.EMAIL, "smtp", NotificationStatus.SENT, "msg-" + requestId,
                null, null,
                java.time.Instant.now(), java.time.Instant.now(), java.time.Instant.now(),
                null);
    }
}
