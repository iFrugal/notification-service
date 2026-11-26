package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.IdempotencyInProgressException;
import com.lazydevs.notification.api.exception.IdempotencyKeyReusedException;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.idempotency.IdempotencyStatus;
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
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DD-32: fingerprint and key reuse, failure policy, in-progress timeout,
 * all against the real Caffeine store.
 */
@ExtendWith(MockitoExtension.class)
class DefaultNotificationServiceIdempotencyPolicyTest {

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
        lenient().when(templateEngine.render(any())).thenReturn(
                new RenderedContent("subj", "body", null, "WELCOME"));
        lenient().when(providerRegistry.getProvider(anyString(), any(Channel.class), any())).thenReturn(provider);
        lenient().when(provider.getProviderName()).thenReturn("smtp");
        lenient().when(rateLimiter.tryConsume(any())).thenReturn(RateLimiter.Decision.allow());
    }

    // ---------------------------------------------------------------- key reuse

    @Test
    void keyReuse_withADifferentRequest_isRejectedWhenConfigured() {
        properties.getIdempotency().setRejectKeyReuse(true);
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-1"));
        DefaultNotificationService service = service();
        NotificationRequest first = request("k-reuse", "Ada");
        service.send(first);

        assertThatThrownBy(() -> service.send(request("k-reuse", "Grace")))
                .isInstanceOf(IdempotencyKeyReusedException.class)
                .hasFieldOrPropertyWithValue("existingNotificationId", first.getRequestId())
                .hasFieldOrPropertyWithValue("errorCode", IdempotencyKeyReusedException.ERROR_CODE);
        verify(provider, times(1)).send(any(), any());
    }

    @Test
    void keyReuse_withTheSameRequest_replays_evenWhenCorrelationIdAndMetadataDiffer() {
        properties.getIdempotency().setRejectKeyReuse(true);
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-1"));
        DefaultNotificationService service = service();
        service.send(request("k-same", "Ada"));
        NotificationRequest again = request("k-same", "Ada");
        again.setCorrelationId("another-correlation");
        again.setMetadata(Map.of("trace", "x"));

        NotificationResponse replay = service.send(again);

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.status()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void keyReuse_isNotCheckedByDefault_andTheFirstResponseIsReplayed() {
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-1"));
        DefaultNotificationService service = service();
        service.send(request("k-default", "Ada"));

        NotificationResponse replay = service.send(request("k-default", "Grace"));

        assertThat(replay.idempotentReplay()).isTrue();
        verify(provider, times(1)).send(any(), any());
    }

    @Test
    void keyReuse_recordWithoutFingerprint_isNeverCompared() {
        properties.getIdempotency().setRejectKeyReuse(true);
        IdempotencyKey key = new IdempotencyKey("acme", null, "k-legacy");
        store.markInProgress(key, "legacy-req");
        store.markComplete(key, NotificationResponse.success(request("k-legacy", "Ada"), "smtp", "m-0"));

        NotificationResponse replay = service().send(request("k-legacy", "Somebody else"));

        assertThat(replay.idempotentReplay()).isTrue();
    }

    @Test
    void keyReuse_detectedAfterALostClaimRace() {
        properties.getIdempotency().setRejectKeyReuse(true);
        IdempotencyStore racing = spy(store);
        IdempotencyKey key = new IdempotencyKey("acme", null, "k-race");
        // The first lookup sees nothing; another request with another payload claims the key in between.
        when(racing.findExisting(key)).thenReturn(Optional.empty()).thenCallRealMethod();
        store.markInProgress(key, "winner", "other-fingerprint");

        assertThatThrownBy(() -> service(racing, Clock.systemUTC()).send(request("k-race", "Ada")))
                .isInstanceOf(IdempotencyKeyReusedException.class)
                .hasFieldOrPropertyWithValue("existingNotificationId", "winner");
    }

    @Test
    void theStoredFingerprintIsTheOriginalRequests() {
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-1"));
        NotificationRequest request = request("k-fp", "Ada");
        String expected = RequestFingerprint.of(request("k-fp", "Ada"));

        service().send(request);

        assertThat(store.findExisting(new IdempotencyKey("acme", null, "k-fp")).orElseThrow().fingerprint())
                .isEqualTo(expected);
    }

    // ---------------------------------------------------------------- failure policy

    @Test
    void releaseTransient_releasesAfterATransientFailure() {
        properties.getIdempotency().setFailurePolicy(IdempotencyFailurePolicy.RELEASE_TRANSIENT);
        when(provider.send(any(), any()))
                .thenReturn(SendResult.failure("SMTP_421", "busy", FailureType.TRANSIENT))
                .thenReturn(SendResult.success("m-2"));
        DefaultNotificationService service = service(store, Clock.systemUTC(), Optional.of(rateLimiter));

        NotificationResponse first = service.send(request("k-transient", "Ada"));
        NotificationResponse retry = service.send(request("k-transient", "Ada"));

        assertThat(first.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(first.failureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(retry.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(retry.idempotentReplay()).isNull();
        verify(provider, times(2)).send(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = FailureType.class, names = {"PERMANENT", "AMBIGUOUS", "UNKNOWN"})
    void releaseTransient_keepsAndReplaysOtherFailures(FailureType type) {
        properties.getIdempotency().setFailurePolicy(IdempotencyFailurePolicy.RELEASE_TRANSIENT);
        when(provider.send(any(), any()))
                .thenReturn(SendResult.failure("ACS_X", "mailbox a@example.com rejected", type, "op-1"));
        DefaultNotificationService service = service();
        NotificationRequest firstRequest = request("k-kept-" + type, "Ada");

        NotificationResponse first = service.send(firstRequest);
        NotificationResponse replay = service.send(request("k-kept-" + type, "Ada"));

        assertThat(first.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(replay.failureType()).isEqualTo(type);
        assertThat(replay.requestId()).isEqualTo(firstRequest.getRequestId());
        assertThat(replay.providerMessageId()).isEqualTo("op-1");
        assertThat(replay.errorMessage()).doesNotContain("a@example.com");
        verify(provider, times(1)).send(any(), any());
        verify(auditService).recordDuplicateHit(any(), any());
    }

    @Test
    void releaseTransient_legacyFailedRecordWithoutFailureType_isReplayed() {
        properties.getIdempotency().setFailurePolicy(IdempotencyFailurePolicy.RELEASE_TRANSIENT);
        IdempotencyKey key = new IdempotencyKey("acme", null, "k-legacy-failed");
        store.markInProgress(key, "legacy");
        store.markComplete(key, NotificationResponse.failure(request("x", "Ada"), "smtp", "E", "m"));

        NotificationResponse replay = service().send(request("k-legacy-failed", "Ada"));

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.status()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void releaseTransient_leftoverTransientRecord_isReleasedOnLookup() {
        properties.getIdempotency().setFailurePolicy(IdempotencyFailurePolicy.RELEASE_TRANSIENT);
        IdempotencyKey key = new IdempotencyKey("acme", null, "k-leftover");
        store.markInProgress(key, "leftover");
        store.markComplete(key, NotificationResponse.failure(request("x", "Ada"), "smtp", "E", "m")
                .withFailureType(FailureType.TRANSIENT));
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-3"));

        NotificationResponse response = service().send(request("k-leftover", "Ada"));

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(response.idempotentReplay()).isNull();
    }

    @Test
    void keepPolicy_answersARetryWith409() {
        properties.getIdempotency().setFailurePolicy(IdempotencyFailurePolicy.KEEP);
        when(provider.send(any(), any())).thenReturn(SendResult.failure("E", "m", FailureType.TRANSIENT));
        DefaultNotificationService service = service();
        NotificationRequest first = request("k-keep", "Ada");
        service.send(first);

        assertThatThrownBy(() -> service.send(request("k-keep", "Ada")))
                .isInstanceOf(IdempotencyInProgressException.class)
                .hasFieldOrPropertyWithValue("inProgressNotificationId", first.getRequestId());
    }

    @Test
    void failurePolicyWinsOverTheDeprecatedAlias() {
        properties.getIdempotency().setRetryAfterFailure(true);
        properties.getIdempotency().setFailurePolicy(IdempotencyFailurePolicy.KEEP);
        when(provider.send(any(), any())).thenReturn(SendResult.failure("E", "m", FailureType.TRANSIENT));
        DefaultNotificationService service = service();
        service.send(request("k-conflict", "Ada"));

        assertThatThrownBy(() -> service.send(request("k-conflict", "Ada")))
                .isInstanceOf(IdempotencyInProgressException.class);
    }

    @Test
    void storeErrorOnRelease_isSwallowed() {
        IdempotencyStore failing = spy(store);
        doThrow(new IllegalStateException("store down")).when(failing).release(any(), anyString());
        when(provider.send(any(), any())).thenReturn(SendResult.failure("E", "m", FailureType.TRANSIENT));

        NotificationResponse response = service(failing, Clock.systemUTC()).send(request("k-release-err", "Ada"));

        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void completeRecordWithoutAResponse_isTreatedAsAFailureAndReleased() {
        IdempotencyStore odd = spy(store);
        IdempotencyKey key = new IdempotencyKey("acme", null, "k-empty");
        when(odd.findExisting(key))
                .thenReturn(Optional.of(new IdempotencyRecord("old", IdempotencyStatus.COMPLETE, null, Instant.now())))
                .thenCallRealMethod();
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-4"));

        NotificationResponse response = service(odd, Clock.systemUTC()).send(request("k-empty", "Ada"));

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        verify(odd).release(key, "old");
    }

    // ---------------------------------------------------------------- in-progress timeout

    @Test
    void inProgressTimeout_settlesAStaleClaim_asAmbiguousFailure_andReplaysIt() {
        properties.getIdempotency().setInProgressTimeout(Duration.ofMinutes(5));
        IdempotencyKey key = new IdempotencyKey("acme", null, "k-stale");
        store.markInProgress(key, "stuck-req", RequestFingerprint.of(request("k-stale", "Ada")));
        DefaultNotificationService later = service(store, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(6)));

        NotificationResponse replay = later.send(request("k-stale", "Ada"));

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.requestId()).isEqualTo("stuck-req");
        assertThat(replay.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(replay.errorCode()).isEqualTo("IN_PROGRESS_TIMEOUT");
        assertThat(replay.failureType()).isEqualTo(FailureType.AMBIGUOUS);
        verify(provider, never()).send(any(), any());
        ArgumentCaptor<NotificationResponse> audited = ArgumentCaptor.forClass(NotificationResponse.class);
        verify(auditService).recordOutcome(audited.capture());
        assertThat(audited.getValue().requestId()).isEqualTo("stuck-req");
        assertThat(audited.getValue().failureType()).isEqualTo(FailureType.AMBIGUOUS);

        IdempotencyRecord settled = store.findExisting(key).orElseThrow();
        assertThat(settled.status()).isEqualTo(IdempotencyStatus.COMPLETE);
        assertThat(settled.fingerprint()).isNotNull();
        // Never released, even under RELEASE_ALL: the next request replays it too.
        NotificationResponse again = later.send(request("k-stale", "Ada"));
        assertThat(again.errorCode()).isEqualTo("IN_PROGRESS_TIMEOUT");
        assertThat(again.idempotentReplay()).isTrue();
        verify(provider, never()).send(any(), any());
    }

    @Test
    void inProgressTimeout_youngClaim_isStillA409() {
        properties.getIdempotency().setInProgressTimeout(Duration.ofMinutes(5));
        store.markInProgress(new IdempotencyKey("acme", null, "k-young"), "busy-req");

        assertThatThrownBy(() -> service().send(request("k-young", "Ada")))
                .isInstanceOf(IdempotencyInProgressException.class)
                .hasFieldOrPropertyWithValue("inProgressNotificationId", "busy-req");
    }

    @Test
    void inProgressTimeout_unset_keepsThe409ForOldClaims() {
        store.markInProgress(new IdempotencyKey("acme", null, "k-unset"), "busy-req");
        DefaultNotificationService later = service(store, Clock.offset(Clock.systemUTC(), Duration.ofDays(1)));

        assertThatThrownBy(() -> later.send(request("k-unset", "Ada")))
                .isInstanceOf(IdempotencyInProgressException.class);
    }

    @Test
    void inProgressTimeout_storeThatCannotSettle_keepsThe409() {
        properties.getIdempotency().setInProgressTimeout(Duration.ofMinutes(5));
        IdempotencyStore legacy = spy(store);
        doReturn(false).when(legacy).settleInProgress(any(), anyString(), any());
        store.markInProgress(new IdempotencyKey("acme", null, "k-legacy-store"), "busy-req");
        DefaultNotificationService later = service(legacy, Clock.offset(Clock.systemUTC(), Duration.ofHours(1)));

        assertThatThrownBy(() -> later.send(request("k-legacy-store", "Ada")))
                .isInstanceOf(IdempotencyInProgressException.class)
                .hasFieldOrPropertyWithValue("inProgressNotificationId", "busy-req");
    }

    @Test
    void inProgressTimeout_lostSettleRace_answersFromTheCompletedRecord() {
        properties.getIdempotency().setInProgressTimeout(Duration.ofMinutes(5));
        IdempotencyKey key = new IdempotencyKey("acme", null, "k-finished");
        store.markInProgress(key, "slow-req");
        IdempotencyStore racing = spy(store);
        doAnswer(inv -> {
            // The slow first attempt completes just before our settle.
            store.markComplete(key, NotificationResponse.success(request("k-finished", "Ada"), "smtp", "m-9"));
            return false;
        }).when(racing).settleInProgress(any(), anyString(), any());
        DefaultNotificationService later = service(racing, Clock.offset(Clock.systemUTC(), Duration.ofHours(1)));

        NotificationResponse replay = later.send(request("k-finished", "Ada"));

        assertThat(replay.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(replay.idempotentReplay()).isTrue();
    }

    @Test
    void inProgressTimeout_recordGoneAfterLostSettle_dispatchesFresh() {
        properties.getIdempotency().setInProgressTimeout(Duration.ofMinutes(5));
        IdempotencyKey key = new IdempotencyKey("acme", null, "k-gone");
        store.markInProgress(key, "slow-req");
        IdempotencyStore racing = spy(store);
        doAnswer(inv -> {
            store.markComplete(key, NotificationResponse.failure(request("k-gone", "Ada"), "smtp", "E", "m"));
            store.release(key, "slow-req");
            return false;
        }).when(racing).settleInProgress(any(), anyString(), any());
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-10"));

        NotificationResponse response = service(racing, Clock.offset(Clock.systemUTC(), Duration.ofHours(1)))
                .send(request("k-gone", "Ada"));

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(response.idempotentReplay()).isNull();
    }

    @Test
    void inProgressTimeout_auditFailureDuringSettle_doesNotFailTheReplay() {
        properties.getIdempotency().setInProgressTimeout(Duration.ofMinutes(5));
        store.markInProgress(new IdempotencyKey("acme", null, "k-audit-down"), "stuck");
        doThrow(new IllegalStateException("audit down")).when(auditService).recordOutcome(any());

        NotificationResponse replay = service(store, Clock.offset(Clock.systemUTC(), Duration.ofHours(1)))
                .send(request("k-audit-down", "Ada"));

        assertThat(replay.errorCode()).isEqualTo("IN_PROGRESS_TIMEOUT");
    }

    // ---------------------------------------------------------------- scrubbing

    @Test
    void customScrubber_isAppliedToTheStoredResponseAndTheAudit() {
        properties.getIdempotency().setFailurePolicy(IdempotencyFailurePolicy.KEEP);
        when(provider.send(any(), any())).thenReturn(SendResult.failure("E", "secret detail", FailureType.PERMANENT));
        MessageScrubber dropAll = message -> null;
        DefaultNotificationService service = new DefaultNotificationService(
                properties, providerRegistry, templateEngine, auditService, Optional.of(store), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                NotificationServiceExtensions.of(dropAll, Clock.systemUTC()));

        NotificationResponse response = service.send(request("k-scrub", "Ada"));

        assertThat(response.errorMessage()).isEqualTo("secret detail");
        assertThat(store.findExisting(new IdempotencyKey("acme", null, "k-scrub")).orElseThrow()
                .response().errorMessage()).isNull();
        verify(auditService).recordOutcome(eq(response.withErrorMessage(null)));
    }

    // ---------------------------------------------------------------- helpers

    private DefaultNotificationService service() {
        return service(store, Clock.systemUTC());
    }

    private DefaultNotificationService service(IdempotencyStore idempotencyStore, Clock clock) {
        return service(idempotencyStore, clock, Optional.empty());
    }

    private DefaultNotificationService service(IdempotencyStore idempotencyStore, Clock clock,
                                               Optional<RateLimiter> limiter) {
        return new DefaultNotificationService(
                properties, providerRegistry, templateEngine, auditService,
                Optional.of(idempotencyStore), limiter, Optional.empty(), Optional.empty(), Optional.empty(),
                NotificationServiceExtensions.of(MessageScrubber.placeholders(), clock));
    }

    private static NotificationRequest request(String idempotencyKey, String name) {
        return NotificationRequest.builder()
                .requestId("req-" + System.nanoTime())
                .tenantId("acme")
                .notificationType("WELCOME")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .templateData(Map.of("name", name))
                .idempotencyKey(idempotencyKey)
                .build();
    }
}
