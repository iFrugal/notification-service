package com.lazydevs.notification.rest.controller;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.caller.CallerRegistry;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.service.NotificationAuditService;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the DD-19 bulk DLQ replay endpoint.
 *
 * <p>Covers dry-run vs live, the tenantId requirement, partial
 * success (some entries succeed, some fail), DLQ-disabled,
 * cross-tenant isolation (entries from a different tenant aren't
 * touched), and the claim / acknowledge / release cycle: live mode
 * claims entries before replay, removes them on success, releases them
 * on failure, reports entries another replay holds as {@code CLAIMED},
 * and concurrent batches replay disjoint entries.
 */
class AdminControllerBulkReplayTest {

    private static final Duration LEASE = Duration.ofSeconds(90);

    private NotificationService notificationService;
    private NotificationProperties properties;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        properties = new NotificationProperties();
        properties.setDefaultTenant("default-tenant");
        properties.getDeadLetter().setEnabled(true);
        properties.getDeadLetter().setReplayLease(LEASE);
    }

    private AdminController controller(DeadLetterStore store) {
        return new AdminController(
                properties,
                mock(ProviderRegistry.class),
                mock(NotificationTemplateEngine.class),
                mock(CallerRegistry.class),
                Optional.<RateLimiter>empty(),
                Optional.ofNullable(store),
                Optional.<DeliveryEventStore>empty(),
                notificationService,
                mock(NotificationAuditService.class));
    }

    private MockMvc mvc(DeadLetterStore store) {
        return MockMvcBuilders.standaloneSetup(controller(store)).build();
    }

    @Test
    void dryRun_returnsPreviewWithoutClaimingSendingOrRemoving() throws Exception {
        RecordingLeasingDeadLetterStore store = new RecordingLeasingDeadLetterStore(
                entry("req-1", "acme"),
                entry("req-2", "acme"));

        mvc(store).perform(post("/api/v1/admin/dead-letter/replay-batch")
                        .param("tenantId", "acme")
                        .param("dryRun", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("dry-run"))
                .andExpect(jsonPath("$.tenantId").value("acme"))
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.entries[0].originalRequestId").value("req-1"))
                .andExpect(jsonPath("$.entries[0].failureType").exists())
                .andExpect(jsonPath("$.entries[0].status").doesNotExist())
                .andExpect(jsonPath("$.replayed").doesNotExist());

        verify(notificationService, never()).send(any());
        assertThat(store.claims()).isEmpty();
        assertThat(store.removed()).isEmpty();
        assertThat(store.released()).isEmpty();
    }

    @Test
    void live_replaysEachEntry_removesSuccessful_releasesFailed() throws Exception {
        RecordingLeasingDeadLetterStore store = new RecordingLeasingDeadLetterStore(
                entry("req-success-1", "acme"),
                entry("req-fail-1", "acme"),
                entry("req-success-2", "acme"));

        // First and third entries succeed, second fails at the provider.
        when(notificationService.send(any())).thenAnswer(inv -> {
            NotificationRequest req = inv.getArgument(0);
            return "req-fail-1".equals(req.getReplayOf()) ? failed(req) : sent(req);
        });

        mvc(store).perform(post("/api/v1/admin/dead-letter/replay-batch")
                        .param("tenantId", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("live"))
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.replayed").value(2))
                .andExpect(jsonPath("$.stillDeadLettered").value(1))
                .andExpect(jsonPath("$.claimed").value(0))
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].status").value("SENT"))
                .andExpect(jsonPath("$.entries[0].removedFromDlq").value(true))
                .andExpect(jsonPath("$.entries[1].status").value("FAILED"))
                .andExpect(jsonPath("$.entries[1].removedFromDlq").value(false))
                .andExpect(jsonPath("$.entries[1].errorCode").value("PROVIDER_TIMEOUT"));

        // One claim for the whole batch, with the configured lease.
        assertThat(store.claims()).singleElement().satisfies(c -> {
            assertThat(c.tenantId()).isEqualTo("acme");
            assertThat(c.lease()).isEqualTo(LEASE);
            assertThat(c.requestIds()).containsExactly("req-success-1", "req-fail-1", "req-success-2");
        });
        // Successful entries removed (the acknowledgement), the failed one
        // released so another attempt can take it straight away.
        assertThat(store.removed()).containsExactly("req-success-1", "req-success-2");
        assertThat(store.released()).containsExactly("req-fail-1");
        assertThat(store.claim("acme", 10, LEASE))
                .extracting(e -> e.request().getRequestId())
                .containsExactly("req-fail-1");
    }

    @Test
    void live_perEntryExceptionDoesNotShortCircuit_andReleasesThatEntry() throws Exception {
        // A thrown exception (e.g. rate-limit) on one entry should
        // still let the rest of the batch run. The HTTP code stays
        // 200; the entry shows status:FAILED with the exception
        // message.
        RecordingLeasingDeadLetterStore store = new RecordingLeasingDeadLetterStore(
                entry("req-1", "acme"),
                entry("req-2", "acme"));
        when(notificationService.send(any()))
                .thenThrow(new RuntimeException("rate-limit hit"))
                .thenAnswer(inv -> sent(inv.getArgument(0)));

        mvc(store).perform(post("/api/v1/admin/dead-letter/replay-batch")
                        .param("tenantId", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(1))
                .andExpect(jsonPath("$.stillDeadLettered").value(1))
                .andExpect(jsonPath("$.entries[0].status").value("FAILED"))
                .andExpect(jsonPath("$.entries[0].errorMessage").exists());

        assertThat(store.released()).containsExactly("req-1");
        assertThat(store.removed()).containsExactly("req-2");
    }

    @Test
    void live_isTenantScoped_doesNotTouchOtherTenants() throws Exception {
        // acme has 2 entries, globex has 1. tenantId=acme should only
        // replay acme's; globex's entry must not appear in the
        // results nor be sent.
        RecordingLeasingDeadLetterStore store = new RecordingLeasingDeadLetterStore(
                entry("req-acme-1", "acme"),
                entry("req-globex-1", "globex"),
                entry("req-acme-2", "acme"));
        when(notificationService.send(any())).thenAnswer(inv -> sent(inv.getArgument(0)));

        mvc(store).perform(post("/api/v1/admin/dead-letter/replay-batch")
                        .param("tenantId", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.replayed").value(2))
                .andExpect(jsonPath("$.claimed").value(0))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].originalRequestId").value("req-acme-1"))
                .andExpect(jsonPath("$.entries[1].originalRequestId").value("req-acme-2"));

        // Service only called twice — globex's entry was filtered out.
        verify(notificationService, times(2)).send(any());
        assertThat(store.findByRequestId("globex", "req-globex-1")).isPresent();
    }

    @Test
    void live_entryClaimedByAnotherReplay_isSkippedAndReportedAsClaimed() throws Exception {
        RecordingLeasingDeadLetterStore store = new RecordingLeasingDeadLetterStore(
                entry("req-1", "acme"),
                entry("req-busy", "acme"),
                entry("req-3", "acme"));
        store.holdElsewhere("acme", "req-busy");
        when(notificationService.send(any())).thenAnswer(inv -> sent(inv.getArgument(0)));

        mvc(store).perform(post("/api/v1/admin/dead-letter/replay-batch")
                        .param("tenantId", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.replayed").value(2))
                .andExpect(jsonPath("$.stillDeadLettered").value(0))
                .andExpect(jsonPath("$.claimed").value(1))
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[2].originalRequestId").value("req-busy"))
                .andExpect(jsonPath("$.entries[2].status").value("CLAIMED"))
                .andExpect(jsonPath("$.entries[2].removedFromDlq").value(false))
                .andExpect(jsonPath("$.entries[2].errorMessage").doesNotExist());

        // Never sent, never removed, and not released: the lease is not ours.
        verify(notificationService, times(2)).send(any());
        assertThat(store.removed()).containsExactly("req-1", "req-3");
        assertThat(store.released()).isEmpty();
        assertThat(store.findByRequestId("acme", "req-busy")).isPresent();
    }

    @Test
    void live_claimThatFillsTheLimit_reportsNothingAsClaimedElsewhere() throws Exception {
        // limit=1 over two unclaimed entries: the second one simply did not
        // fit and must not be misreported as held by another replay.
        RecordingLeasingDeadLetterStore store = new RecordingLeasingDeadLetterStore(
                entry("req-1", "acme"),
                entry("req-2", "acme"));
        when(notificationService.send(any())).thenAnswer(inv -> sent(inv.getArgument(0)));

        mvc(store).perform(post("/api/v1/admin/dead-letter/replay-batch")
                        .param("tenantId", "acme")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1))
                .andExpect(jsonPath("$.claimed").value(0))
                .andExpect(jsonPath("$.entries.length()").value(1));
    }

    @Test
    void concurrentBatches_replayDisjointEntries_eachExactlyOnce() throws Exception {
        List<DeadLetterEntry> initial = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            initial.add(entry("req-" + i, "acme"));
        }
        RecordingLeasingDeadLetterStore store =
                new RecordingLeasingDeadLetterStore(initial.toArray(DeadLetterEntry[]::new));

        // Every send waits until both batches have claimed, so the two
        // batches are guaranteed to overlap in time.
        CountDownLatch bothClaimed = new CountDownLatch(2);
        List<String> sentOriginals = new CopyOnWriteArrayList<>();
        when(notificationService.send(any())).thenAnswer(inv -> {
            assertThat(bothClaimed.await(10, TimeUnit.SECONDS)).isTrue();
            NotificationRequest req = inv.getArgument(0);
            sentOriginals.add(req.getReplayOf());
            return sent(req);
        });
        DeadLetterStore countingStore = new CountingClaims(store, bothClaimed);
        AdminController countingController = controller(countingStore);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<Map<String, Object>>> a =
                    pool.submit(() -> countingController.replayDeadLetterBatch("acme", 3, false));
            Future<ResponseEntity<Map<String, Object>>> b =
                    pool.submit(() -> countingController.replayDeadLetterBatch("acme", 3, false));
            Map<String, Object> bodyA = a.get(30, TimeUnit.SECONDS).getBody();
            Map<String, Object> bodyB = b.get(30, TimeUnit.SECONDS).getBody();

            assertThat(bodyA).containsEntry("replayed", 3).containsEntry("claimed", 0);
            assertThat(bodyB).containsEntry("replayed", 3).containsEntry("claimed", 0);
        } finally {
            pool.shutdownNow();
        }

        List<RecordingLeasingDeadLetterStore.Claim> claims = store.claims();
        assertThat(claims).hasSize(2);
        Set<String> first = new HashSet<>(claims.get(0).requestIds());
        Set<String> second = new HashSet<>(claims.get(1).requestIds());
        assertThat(first).hasSize(3).doesNotContainAnyElementsOf(second);
        assertThat(second).hasSize(3);
        assertThat(sentOriginals).hasSize(6).doesNotHaveDuplicates();
        assertThat(store.size()).isZero();
    }

    @Test
    void missingTenantId_returns400() throws Exception {
        mvc(new RecordingLeasingDeadLetterStore()).perform(post("/api/v1/admin/dead-letter/replay-batch"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());

        verify(notificationService, never()).send(any());
    }

    @Test
    void blankTenantId_returns400() throws Exception {
        mvc(new RecordingLeasingDeadLetterStore()).perform(post("/api/v1/admin/dead-letter/replay-batch")
                        .param("tenantId", "   "))
                .andExpect(status().isBadRequest());

        verify(notificationService, never()).send(any());
    }

    @Test
    void dlqDisabled_returns503() throws Exception {
        mvc(null).perform(post("/api/v1/admin/dead-letter/replay-batch")
                        .param("tenantId", "acme"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void storeWithoutSnapshotOrClaim_explainsInsteadOfReplaying() throws Exception {
        DeadLetterStore opaque = mock(DeadLetterStore.class);
        when(opaque.snapshot()).thenReturn(Optional.empty());
        when(opaque.claim(any(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(List.of());

        mvc(opaque).perform(post("/api/v1/admin/dead-letter/replay-batch")
                        .param("tenantId", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("live"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("does not support snapshot iteration")));

        verify(notificationService, never()).send(any());
    }

    @Test
    void limit_clampsAt1000() throws Exception {
        // Passing limit=10000 must clamp at 1000 internally: the store
        // sees a claim of 1000, and the absurd value causes no error.
        RecordingLeasingDeadLetterStore store = new RecordingLeasingDeadLetterStore(entry("req-1", "acme"));
        when(notificationService.send(any())).thenAnswer(inv -> sent(inv.getArgument(0)));

        mvc(store).perform(post("/api/v1/admin/dead-letter/replay-batch")
                        .param("tenantId", "acme")
                        .param("limit", "10000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1));

        assertThat(store.claims()).singleElement()
                .satisfies(c -> assertThat(c.limit()).isEqualTo(1_000));
    }

    /** Delegates to a store and counts down a latch after each batch claim. */
    private record CountingClaims(DeadLetterStore delegate, CountDownLatch latch) implements DeadLetterStore {
        @Override
        public void add(DeadLetterEntry entry) {
            delegate.add(entry);
        }

        @Override
        public Optional<List<DeadLetterEntry>> snapshot() {
            return delegate.snapshot();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean remove(String tenantId, String requestId) {
            return delegate.remove(tenantId, requestId);
        }

        @Override
        public List<DeadLetterEntry> claim(String tenantId, int limit, Duration lease) {
            List<DeadLetterEntry> claimed = delegate.claim(tenantId, limit, lease);
            latch.countDown();
            return claimed;
        }

        @Override
        public void release(String tenantId, String requestId) {
            delegate.release(tenantId, requestId);
        }
    }

    private static NotificationResponse sent(NotificationRequest req) {
        return new NotificationResponse(
                req.getRequestId(), null, req.getTenantId(), req.getCallerId(),
                req.getChannel(), "smtp", NotificationStatus.SENT, "msg-1",
                null, null, Instant.now(), Instant.now(), Instant.now(), null);
    }

    private static NotificationResponse failed(NotificationRequest req) {
        return new NotificationResponse(
                req.getRequestId(), null, req.getTenantId(), req.getCallerId(),
                req.getChannel(), "smtp", NotificationStatus.FAILED, null,
                "PROVIDER_TIMEOUT", "smtp 421",
                Instant.now(), Instant.now(), null, null);
    }

    private static DeadLetterEntry entry(String requestId, String tenantId) {
        NotificationRequest req = NotificationRequest.builder()
                .requestId(requestId)
                .tenantId(tenantId)
                .callerId("billing-svc")
                .notificationType("ORDER_CONFIRMATION")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .build();
        NotificationResponse resp = new NotificationResponse(
                requestId, null, tenantId, "billing-svc", Channel.EMAIL,
                "smtp", NotificationStatus.FAILED, null, "ERR", "boom",
                Instant.now(), Instant.now(), null, null);
        return new DeadLetterEntry(Instant.now(), req, resp, 3, FailureType.TRANSIENT);
    }
}
