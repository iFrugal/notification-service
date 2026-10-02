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
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.idempotency.CaffeineIdempotencyStore;
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
 *   <li>COMPLETE + FAILED status → released, then fresh dispatch.</li>
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
        // The failure path reads idempotency.retryAfterFailure (default true).
        lenient().when(properties.getIdempotency()).thenReturn(new NotificationProperties.IdempotencyProperties());
        // Rate limiter not configured in this suite — DD-12 wires it as an
        // Optional that's empty when notification.rate-limit.enabled=false,
        // matching production behaviour for tests that don't exercise it.
        // DD-13: retry + DLQ also wired as Optionals; this suite leaves
        // both empty so existing assertions about single-attempt
        // dispatch + no DLQ recording continue to hold.
        service = new DefaultNotificationService(
                properties, providerRegistry, templateEngine, auditService,
                Optional.of(idempotencyStore), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
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
        // callerId is null because baseRequest() doesn't set X-Service-Id —
        // dedup scope reduces to (tenantId, null, idempotencyKey), matching
        // pre-DD-11 behaviour for callers that don't identify themselves.
        assertThat(keyCaptor.getValue().callerId()).isNull();
    }

    @Test
    void send_withCallerId_propagatesIntoIdempotencyKey() {
        // DD-11: when the request carries a callerId, it becomes part of
        // the dedup tuple. Two requests with the same idempotency key but
        // different callers will *not* be treated as duplicates.
        NotificationRequest req = baseRequest("idem-with-caller");
        req.setCallerId("billing-svc");
        when(idempotencyStore.findExisting(any())).thenReturn(Optional.empty());
        when(idempotencyStore.markInProgress(any(), anyString())).thenReturn(true);
        stubProviderHappyPath();

        service.send(req);

        ArgumentCaptor<IdempotencyKey> keyCaptor = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(idempotencyStore).markInProgress(keyCaptor.capture(), anyString());
        assertThat(keyCaptor.getValue().tenantId()).isEqualTo("acme");
        assertThat(keyCaptor.getValue().callerId()).isEqualTo("billing-svc");
        assertThat(keyCaptor.getValue().idempotencyKey()).isEqualTo("idem-with-caller");
    }

    @Test
    void send_completedReplayable_returnsCached_noProviderCall() throws Exception {
        NotificationRequest req = baseRequest("idem-replay");
        NotificationResponse cached = sentResponse("original-req-id");
        IdempotencyRecord cachedRecord = new IdempotencyRecord(
                "original-req-id", IdempotencyStatus.COMPLETE, cached, Instant.now());
        when(idempotencyStore.findExisting(any())).thenReturn(Optional.of(cachedRecord));

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
        verify(auditService).recordDuplicateHit(eq(req), eq(cachedRecord));
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
    void send_retryAfterFailedAttempt_dispatchesAgain_withRealStore() {
        // Reproduction for the 1.1.1 bug: the FAILED row made markInProgress
        // return false, so the retry got a 409 until the TTL elapsed.
        NotificationProperties realProperties = new NotificationProperties();
        CaffeineIdempotencyStore store = new CaffeineIdempotencyStore(realProperties);
        DefaultNotificationService realStoreService = serviceWith(realProperties, store);
        stubProvider();
        when(provider.send(any(), any()))
                .thenReturn(SendResult.failure("SMTP_421", "try again later", FailureType.TRANSIENT))
                .thenReturn(SendResult.success("provider-msg-2"));

        NotificationResponse first = realStoreService.send(baseRequest("idem-failed"));
        NotificationResponse retry = realStoreService.send(baseRequest("idem-failed"));

        assertThat(first.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(retry.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(retry.idempotentReplay()).isNull();
        verify(provider, times(2)).send(any(), any());
        // recordDuplicateHit must NOT fire for a retry after FAILED - it is
        // a genuinely fresh dispatch, not a cache hit.
        verify(auditService, never()).recordDuplicateHit(any(), any());

        // The successful retry is now the cached outcome: a third call replays.
        NotificationResponse third = realStoreService.send(baseRequest("idem-failed"));
        assertThat(third.idempotentReplay()).isTrue();
        assertThat(third.requestId()).isEqualTo(retry.requestId());
        verify(provider, times(2)).send(any(), any());
    }

    @Test
    void send_retryAfterFailedAttempt_withRetryAfterFailureOff_keepsThe409() {
        NotificationProperties realProperties = new NotificationProperties();
        realProperties.getIdempotency().setRetryAfterFailure(false);
        CaffeineIdempotencyStore store = new CaffeineIdempotencyStore(realProperties);
        DefaultNotificationService realStoreService = serviceWith(realProperties, store);
        stubProvider();
        when(provider.send(any(), any()))
                .thenReturn(SendResult.failure("SMTP_421", "try again later", FailureType.TRANSIENT));

        NotificationRequest firstRequest = baseRequest("idem-strict");
        NotificationResponse first = realStoreService.send(firstRequest);

        assertThat(first.status()).isEqualTo(NotificationStatus.FAILED);
        assertThatThrownBy(() -> realStoreService.send(baseRequest("idem-strict")))
                .isInstanceOf(IdempotencyInProgressException.class)
                .hasFieldOrPropertyWithValue("inProgressNotificationId", firstRequest.getRequestId());
        verify(provider, times(1)).send(any(), any());
    }

    @Test
    void send_failedRowFromAnOlderVersion_isReleasedAndRetried() {
        // A 1.1.0 / 1.1.1 node left a COMPLETE + FAILED row behind. The retry
        // releases it (compare-and-delete on its notificationId) and dispatches.
        NotificationProperties realProperties = new NotificationProperties();
        CaffeineIdempotencyStore store = new CaffeineIdempotencyStore(realProperties);
        IdempotencyKey key = new IdempotencyKey("acme", null, "idem-legacy");
        store.markInProgress(key, "prior-req-id");
        store.markComplete(key, failedResponse("prior-req-id"));
        DefaultNotificationService realStoreService = serviceWith(realProperties, store);
        stubProviderHappyPath();

        NotificationResponse retry = realStoreService.send(baseRequest("idem-legacy"));

        assertThat(retry.status()).isEqualTo(NotificationStatus.SENT);
        IdempotencyRecord stored = store.findExisting(key).orElseThrow();
        assertThat(stored.notificationId()).isEqualTo(retry.requestId());
        assertThat(stored.response().status()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void send_failure_withStoreThatCannotRelease_keepsTheCompleteRow() {
        // A third-party store that does not override release() gets the
        // 1.1.x behaviour: the FAILED response is recorded with markComplete.
        when(idempotencyStore.findExisting(any())).thenReturn(Optional.empty());
        when(idempotencyStore.markInProgress(any(), anyString())).thenReturn(true);
        stubProvider();
        when(provider.send(any(), any())).thenReturn(SendResult.failure("SMTP_421", "try again later"));

        NotificationResponse response = service.send(baseRequest("idem-third-party"));

        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
        verify(idempotencyStore).markComplete(any(), any(NotificationResponse.class));
        verify(idempotencyStore).release(any(), eq(response.requestId()));
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
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        NotificationRequest req = baseRequest("idem-disabled");
        stubProviderHappyPath();

        NotificationResponse response = noStoreService.send(req);

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        verifyNoInteractions(idempotencyStore);
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private DefaultNotificationService serviceWith(NotificationProperties props, IdempotencyStore store) {
        return new DefaultNotificationService(
                props, providerRegistry, templateEngine, auditService,
                Optional.of(store), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private void stubProvider() {
        when(templateEngine.render(any())).thenReturn(
                new RenderedContent("subj", "body", null, "ORDER_CONFIRMATION"));
        when(providerRegistry.getProvider(anyString(), any(Channel.class), any()))
                .thenReturn(provider);
        lenient().when(provider.getProviderName()).thenReturn("smtp");
    }

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
                requestId, "corr-" + requestId, "acme", null, Channel.EMAIL,
                "smtp", NotificationStatus.SENT, "msg-" + requestId,
                null, null,
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(60), Instant.now().minusSeconds(60),
                null);
    }

    private static NotificationResponse failedResponse(String requestId) {
        return new NotificationResponse(
                requestId, "corr-" + requestId, "acme", null, Channel.EMAIL,
                "smtp", NotificationStatus.FAILED, null,
                "PROVIDER_ERROR", "smtp 421 — try again later",
                Instant.now().minusSeconds(120), Instant.now().minusSeconds(120), null,
                null);
    }
}
