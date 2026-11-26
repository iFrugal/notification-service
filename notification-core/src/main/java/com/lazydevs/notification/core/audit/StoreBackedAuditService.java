package com.lazydevs.notification.core.audit;

import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.audit.NotificationAuditStore;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationAudit;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.PushRecipient;
import com.lazydevs.notification.api.model.Recipient;
import com.lazydevs.notification.api.model.SmsRecipient;
import com.lazydevs.notification.api.model.WhatsAppRecipient;
import com.lazydevs.notification.api.util.PiiMasking;
import com.lazydevs.notification.core.service.NotificationAuditService;
import lazydevs.persistence.connection.multitenant.TenantContext;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@link NotificationAuditService} behind {@code notification.audit.enabled=true}
 * (DD-33): builds each {@link NotificationAudit} and keeps it in a
 * {@link NotificationAuditStore}.
 *
 * <p>Writes never fail a send: a store error is logged at WARN and
 * swallowed (DD-07 "audit failure shouldn't fail notification"), so the
 * status view of that send may be missing or stale. Reads propagate errors.
 *
 * <p>The record never holds recipient addresses: the summary is
 * {@link PiiMasking#mask(Recipient) masked} and the count is a number.
 * {@code store-request-payload} and {@code store-response-payload} are not
 * implemented; the payload columns stay empty.
 */
@Slf4j
public class StoreBackedAuditService implements NotificationAuditService {

    private final NotificationAuditStore store;
    private final String defaultTenant;
    private final Clock clock;

    public StoreBackedAuditService(NotificationAuditStore store, String defaultTenant, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.defaultTenant = defaultTenant;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public NotificationAudit recordReceived(NotificationRequest request) {
        Instant now = clock.instant();
        NotificationAudit audit = NotificationAudit.builder()
                .id(UUID.randomUUID().toString())
                .requestId(request.getRequestId())
                .correlationId(request.getCorrelationId())
                .tenantId(request.getTenantId())
                .callerId(request.getCallerId())
                .idempotencyKey(request.getIdempotencyKey())
                .replayOf(request.getReplayOf())
                .notificationType(request.getNotificationType())
                .channel(request.getChannel())
                .provider(request.getProvider())
                .recipientSummary(PiiMasking.mask(request.getRecipient()))
                .recipientCount(recipientCount(request.getRecipient()))
                .templateId(request.getTemplateId())
                .metadata(request.getMetadata() == null ? null : new LinkedHashMap<>(request.getMetadata()))
                .status(NotificationStatus.PROCESSING)
                .receivedAt(now)
                .updatedAt(now)
                .build();
        try {
            store.insert(audit);
        } catch (RuntimeException e) {
            log.warn("Audit insert failed for requestId={}: {}", sanitize(request.getRequestId()), e.toString());
        }
        return audit;
    }

    /**
     * The 1.2.0 entry point, scoped to the current {@code TenantContext}
     * tenant (else the default tenant). The service itself calls
     * {@link #recordOutcome(NotificationResponse)}.
     */
    @Override
    public NotificationAudit updateStatus(String requestId, NotificationStatus status, String providerMessageId,
                                          String errorCode, String errorMessage) {
        Instant now = clock.instant();
        recordOutcome(new NotificationResponse(requestId, null, contextTenant(), null, null, null, status,
                providerMessageId, errorCode, errorMessage, null, now, null, null));
        return findByRequestId(requestId).orElse(null);
    }

    @Override
    public void recordOutcome(NotificationResponse response) {
        try {
            store.recordOutcome(response);
        } catch (RuntimeException e) {
            log.warn("Audit update failed for requestId={}: {}", sanitize(response.requestId()), e.toString());
        }
    }

    /** Scoped to the current {@code TenantContext} tenant, else the default tenant. */
    @Override
    public Optional<NotificationAudit> findByRequestId(String requestId) {
        return store.find(contextTenant(), requestId);
    }

    @Override
    public Optional<NotificationAudit> findByRequestId(String tenantId, String requestId) {
        return store.find(tenantId, requestId);
    }

    @Override
    public Optional<NotificationAudit> findByIdempotencyKey(String tenantId, String callerId, String idempotencyKey) {
        return store.findByIdempotencyKey(tenantId, callerId, idempotencyKey);
    }

    @Override
    public Optional<List<NotificationAudit>> findRecent(String tenantId, int limit) {
        return Optional.of(store.findRecent(tenantId, limit));
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * Recipient addresses of a request: to + cc + bcc for email, one number
     * for SMS and WhatsApp, the device tokens (or one token or FID) for
     * push; {@code null} for a push topic or condition, whose audience is
     * unknown.
     */
    static Integer recipientCount(Recipient recipient) {
        return switch (recipient) {
            case null -> null;
            case EmailRecipient email -> nonBlank(List.of(nullToEmpty(email.to())))
                    + nonBlank(email.cc()) + nonBlank(email.bcc());
            case SmsRecipient _ -> 1;
            case WhatsAppRecipient _ -> 1;
            case PushRecipient push -> pushCount(push);
        };
    }

    private static Integer pushCount(PushRecipient push) {
        if (push.deviceTokens() != null && !push.deviceTokens().isEmpty()) {
            return nonBlank(push.deviceTokens());
        }
        if (hasText(push.deviceToken()) || hasText(push.fid())) {
            return 1;
        }
        return null;
    }

    private static int nonBlank(List<String> values) {
        return values == null ? 0 : (int) values.stream().filter(StoreBackedAuditService::hasText).count();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String contextTenant() {
        String tenant = TenantContext.getTenantId();
        return hasText(tenant) ? tenant : defaultTenant;
    }

    /**
     * The value with control characters and line breaks replaced by {@code _}, so a
     * caller- or provider-supplied value cannot forge log lines; {@code "null"} for null.
     */
    private static String sanitize(String s) {
        return s == null ? "null" : s.replaceAll("\\p{Cntrl}", "_").replaceAll("\\R", "_");
    }
}
