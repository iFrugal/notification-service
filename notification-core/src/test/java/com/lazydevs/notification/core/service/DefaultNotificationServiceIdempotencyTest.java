package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.IdempotencyInProgressException;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.idempotency.IdempotencyStatus;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the idempotency-key handling in
 * {@link DefaultNotificationService#send(NotificationRequest)} (DD-10).
 *
 * <p>Covers the four state-table rows from DD-10 §Semantics:
 * <ul>
 *   <li>No prior record → fresh dispatch, store entries written.</li>
 *   <li>IN_PROGRESS → 409 (IdempotencyInProgressException).</li>
 *   <li>COMPLETE + replayable status → return cached response, no provider call.</li>
 *   <li>COMPLETE + FAILED status → fall through to fresh dispatch.</li>
 * </ul>
 * Plus race-loss on {@code markInProgress}, no-key bypass, and
 * markComplete-in-finally for exception paths.
 */
@ExtendWith(MockitoExtension.class)
class DefaultNotificationServiceIdempotencyTest {

    @Mock NotificationProperties properties;
    @Mock ProviderRegistry providerRegistry;
    @Mock NotificationTemplateEngine templateEngine;
    @Mock NotificationAuditService auditService;
    @Mock IdempotencyStore idempotencyStore;
    @Mock NotificationProvider provider;

    private DefaultNotificationService service;

    @BeforeEach
    void setUp() {
        // enrichRequest() reads defaultTenant; lenient because not every test triggers it.
        lenient().when(properties.getDefaultTenant()).thenReturn("default");
        service = new DefaultNotificationService(
                properties, providerRegistry, templateEngine, auditService,
                Optional.of(idempotencyStore));
    }

    @Test
    void send_withoutIdempotencyKey_bypassesStore() {
        NotificationRequest req = baseRequest(null);
        stubProviderHappyPath();

        NotificationResponse response = service.send(req);

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        verifyNoInteractions(idempotencyStore);
    }

    @Test
    void send_firstAttempt_writesInProgressThenComplete() {
        NotificationRequest req = baseRequest("idem-1");
        when(idempotencyStore.findExisting(any())).thenReturn(Optional.empty());
        when(idempotencyStore.markInProgress(any(), anyString())).thenReturn(true);
        stubProviderHappyPath();

        NotificationResponse response = service.send(req);

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        ArgumentCaptor<IdempotencyKey> keyCaptor = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(idempotencyStore).markInProgress(keyCaptor.capture(), eq(req.getRequestId()));
        verify(idempotencyStore).markComplete(eq(keyCaptor.getValue()), any(NotificationResponse.class));
        assertThat(keyCaptor.getValue().tenantId()).isEqualTo("acme");
        assertThat(keyCaptor.getValue().idempotencyKey()).isEqualTo("idem-1");
        assertThat(keyCaptor.getValue().callerId()).isNull();
    }

    @Test
    void send_completedReplayable_returnsCached_noProviderCall() throws Exception {
        NotificationRequest req = baseRequest("idem-replay");
        NotificationResponse cached = sentResponse("original-req-id");
        IdempotencyRecord record = new IdempotencyRecord(
                "original-req-id", IdempotencyStatus.COMPLETE, cached, Instant.now());
        when(idempotencyStore.findExisting(any())).thenReturn(Optional.of(record));

        NotificationResponse response = service.send(req);

        // Replay returns an equivalent response — same requestId, status,
        // timestamps — but stamped with idempotentReplay=true so the
        // controller can surface the X-Idempotent-Replay header.
        assertThat(response.idempotentReplay()).isTrue();
        assertThat(response.requestId()).isEqualTo(cached.requestId());
        assertThat(response.providerMessageId()).isEqualTo(cached.providerMessageId());
        assertThat(response.status()).isEqualTo(cached.status());
        assertThat(response.processedAt()).isEqualTo(cached.processedAt());
        assertThat(response.sentAt()).isEqualTo(cached.sentAt());
        // No provider lookup, no template render, no audit recordReceived.
        verifyNoInteractions(providerRegistry, templateEngine);
        verify(auditService, never()).recordReceived(any());
        verify(auditService).recordDuplicateHit(eq(req), eq(record));
        // markInProgress / markComplete not called — we short-circuited.
        verify(idempotencyStore, never()).markInProgress(any(), anyString());
        verify(idempotencyStore, never()).markComplete(any(), any());
    }

    @Test
    void send_inProgressDuplicate_throws409() {
        NotificationRequest req = baseRequest("idem-inflight");
        IdempotencyRecord inFlight = new IdempotencyRecord(
                "winner-req-id", IdempotencyStatus.IN_PROGRESS, null, Instant.now());
        when(idempotencyStore.findExisting(any())).thenReturn(Optional.of(inFlight));

        assertThatThrownBy(() -> service.send(req))
                .isInstanceOf(IdempotencyInProgressException.class)
                .hasFieldOrPropertyWithValue("inProgressNotificationId", "winner-req-id");

        verifyNoInteractions(providerRegistry, templateEngine);
        verify(idempotencyStore, never()).markInProgress(any(), anyString());
    }

    @Test
    void send_failedPriorAttempt_treatsAsFresh() {
        NotificationRequest req = baseRequest("idem-failed");
        NotificationResponse priorFailure = failedResponse("prior-req-id");
        IdempotencyRecord record = new IdempotencyRecord(
                "prior-req-id", IdempotencyStatus.COMPLETE, priorFailure, Instant.now());
        when(idempotencyStore.findExisting(any())).thenReturn(Optional.of(record));
        when(idempotencyStore.markInProgress(any(), anyString())).thenReturn(true);
        stubProviderHappyPath();

        NotificationResponse response = service.send(req);

        // Fresh dispatch happened — provider was called, response is SENT.
        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        verify(idempotencyStore).markInProgress(any(), anyString());
        verify(idempotencyStore).markComplete(any(), any(NotificationResponse.class));
        // recordDuplicateHit must NOT fire for FAILED replays — those are
        // genuinely fresh dispatches, not cache hits.
        verify(auditService, never()).recordDuplicateHit(any(), any());
    }

    @Test
    void send_lostMarkInProgressRace_throws409WithWinnerId() {
        NotificationRequest req = baseRequest("idem-race");
        // First findExisting (pre-markInProgress) — empty.
        // Second findExisting (after lost race) — winner record.
        IdempotencyRecord winner = new IdempotencyRecord(
                "winner-id", IdempotencyStatus.IN_PROGRESS, null, Instant.now());
        when(idempotencyStore.findExisting(any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(idempotencyStore.markInProgress(any(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.send(req))
                .isInstanceOf(IdempotencyInProgressException.class)
                .hasFieldOrPropertyWithValue("inProgressNotificationId", "winner-id");

        verifyNoInteractions(providerRegistry, templateEngine);
    }

    @Test
    void send_dispatchException_stillCallsMarkComplete() {
        NotificationRequest req = baseRequest("idem-explode");
        when(idempotencyStore.findExisting(any())).thenReturn(Optional.empty());
        when(idempotencyStore.markInProgress(any(), anyString())).thenReturn(true);
        // Simulate provider lookup throwing — the catch block converts to FAILED.
        when(templateEngine.render(any())).thenThrow(new RuntimeException("template engine boom"));

        NotificationResponse response = service.send(req);

        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(response.errorCode()).isEqualTo("INTERNAL_ERROR");
        // Crucially: markComplete still fired in the finally block, releasing the lock.
        verify(idempotencyStore, times(1)).markComplete(any(), any(NotificationResponse.class));
    }

    @Test
    void send_storeAbsent_neverCallsAnyStoreMethod() {
        DefaultNotificationService noStoreService = new DefaultNotificationService(
                properties, providerRegistry, templateEngine, auditService,
                Optional.empty());
        NotificationRequest req = baseRequest("idem-disabled");
        stubProviderHappyPath();

        NotificationResponse response = noStoreService.send(req);

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        verifyNoInteractions(idempotencyStore);
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private void stubProviderHappyPath() {
        // The send-path mock stack: render → resolve provider → provider.send.
        when(templateEngine.render(any())).thenReturn(
                new RenderedContent("subj", "body", null, "ORDER_CONFIRMATION"));
        when(providerRegistry.getProvider(anyString(), any(Channel.class), any()))
                .thenReturn(provider);
        when(provider.send(any(), any())).thenReturn(SendResult.success("provider-msg-1"));
        when(provider.getProviderName()).thenReturn("smtp");
    }

    private NotificationRequest baseRequest(String idempotencyKey) {
        return NotificationRequest.builder()
                .requestId("req-test-" + System.nanoTime())
                .tenantId("acme")
                .notificationType("ORDER_CONFIRMATION")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .idempotencyKey(idempotencyKey)
                .build();
    }

    private static NotificationResponse sentResponse(String requestId) {
        return new NotificationResponse(
                requestId, "corr-" + requestId, "acme", Channel.EMAIL,
                "smtp", NotificationStatus.SENT, "msg-" + requestId,
                null, null,
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(60), Instant.now().minusSeconds(60),
                null);
    }

    private static NotificationResponse failedResponse(String requestId) {
        return new NotificationResponse(
                requestId, "corr-" + requestId, "acme", Channel.EMAIL,
                "smtp", NotificationStatus.FAILED, null,
                "PROVIDER_ERROR", "smtp 421 — try again later",
                Instant.now().minusSeconds(120), Instant.now().minusSeconds(120), null,
                null);
    }
}
