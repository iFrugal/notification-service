package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.IdempotencyInProgressException;
import com.lazydevs.notification.api.exception.NotificationException;
import com.lazydevs.notification.api.exception.RateLimitExceededException;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyRecord;
import com.lazydevs.notification.api.idempotency.IdempotencyStatus;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import lazydevs.persistence.connection.multitenant.TenantContext;
import lazydevs.services.basic.filter.RequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Default implementation of NotificationService.
 */
@Slf4j
@Service
public class DefaultNotificationService implements NotificationService {

    private final NotificationProperties properties;
    private final ProviderRegistry providerRegistry;
    private final NotificationTemplateEngine templateEngine;
    private final NotificationAuditService auditService;
    private final Optional<IdempotencyStore> idempotencyStore;
    private final Optional<RateLimiter> rateLimiter;
    private final Executor asyncExecutor;

    public DefaultNotificationService(
            NotificationProperties properties,
            ProviderRegistry providerRegistry,
            NotificationTemplateEngine templateEngine,
            NotificationAuditService auditService,
            Optional<IdempotencyStore> idempotencyStore,
            Optional<RateLimiter> rateLimiter) {
        this.properties = properties;
        this.providerRegistry = providerRegistry;
        this.templateEngine = templateEngine;
        this.auditService = auditService;
        // Optional<IdempotencyStore>: empty when notification.idempotency.enabled=false
        // (the CaffeineIdempotencyStore @ConditionalOnProperty drops out of the context).
        this.idempotencyStore = idempotencyStore;
        // Optional<RateLimiter>: empty when notification.rate-limit.enabled=false
        // — same wiring shape as idempotency, see DD-12.
        this.rateLimiter = rateLimiter;
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public NotificationResponse send(NotificationRequest request) {
        Instant receivedAt = Instant.now();

        // Enrich first so the idempotency check operates on the resolved
        // requestId/tenantId — those are part of the dedup scope and the
        // 409 response body.
        enrichRequest(request);
        log.debug("Processing notification: requestId={}, channel={}, type={}",
                request.getRequestId(), request.getChannel(), request.getNotificationType());

        // ============== Rate-limit check (DD-12) ==============
        // Run BEFORE the idempotency check per DD-12 §"Why before
        // idempotency": denied requests don't burn an idempotency slot,
        // and replays of completed keys don't burn a token.
        if (rateLimiter.isPresent()) {
            String channel = request.getChannel() != null
                    ? request.getChannel().name().toLowerCase()
                    : "unknown";
            // Anonymous traffic gets bucketed under the literal string
            // "anonymous" so missing-callerId requests share a bucket
            // rather than each carving their own under a null key.
            String callerForBucket = request.getCallerId() != null
                    ? request.getCallerId()
                    : "anonymous";
            RateLimiter.RateLimitKey rlKey = new RateLimiter.RateLimitKey(
                    request.getTenantId(), callerForBucket, channel);
            RateLimiter.Decision decision = rateLimiter.get().tryConsume(rlKey);
            if (!decision.allowed()) {
                throw new RateLimitExceededException(
                        request.getTenantId(), callerForBucket, channel,
                        decision.retryAfter());
            }
        }

        // ============== Idempotency check (DD-10) ==============
        // Run BEFORE the main try/catch so IdempotencyInProgressException
        // (a NotificationException subclass) propagates as 409 rather than
        // being converted into a FAILED response by the broader catch.
        IdempotencyKey idemKey = idempotencyKeyFor(request);
        if (idemKey != null) {
            Optional<IdempotencyRecord> existing = idempotencyStore.get().findExisting(idemKey);
            if (existing.isPresent()) {
                IdempotencyRecord rec = existing.get();
                if (rec.status() == IdempotencyStatus.IN_PROGRESS) {
                    throw new IdempotencyInProgressException(rec.notificationId());
                }
                // status == COMPLETE
                NotificationResponse cached = rec.response();
                if (cached != null && isReplayable(cached.status())) {
                    log.info("Idempotency replay: requestId={}, key={}, originalRequestId={}",
                            request.getRequestId(), request.getIdempotencyKey(), cached.requestId());
                    auditService.recordDuplicateHit(request, rec);
                    // Stamp as a replay so the controller can surface
                    // X-Idempotent-Replay: true (DD-10 §REST-API-behaviour).
                    return NotificationResponse.replayedFrom(cached);
                }
                // FAILED / REJECTED — fall through, treat the new request as fresh.
                log.debug("Idempotency key '{}' had a prior FAILED/REJECTED attempt; proceeding fresh.",
                        request.getIdempotencyKey());
            }
            if (!idempotencyStore.get().markInProgress(idemKey, request.getRequestId())) {
                // Race lost: another caller registered between our findExisting and markInProgress.
                IdempotencyRecord concurrent = idempotencyStore.get().findExisting(idemKey)
                        .orElseThrow(() -> new IllegalStateException(
                                "Idempotency race lost but record not retrievable; should not happen"));
                throw new IdempotencyInProgressException(concurrent.notificationId());
            }
        }

        // ============== Dispatch ==============
        // Held in 'response' for the finally block to close out the
        // idempotency record on every exit path (success or failure).
        NotificationResponse response = null;
        try {
            // Record audit (received)
            auditService.recordReceived(request);

            // Render template
            RenderedContent content = templateEngine.render(request);

            // Get provider
            NotificationProvider provider = providerRegistry.getProvider(
                    request.getTenantId(),
                    request.getChannel(),
                    request.getProvider());

            // Send notification
            SendResult result = provider.send(request, content);

            // Build response
            if (result.success()) {
                response = NotificationResponse.sent(
                        request,
                        provider.getProviderName(),
                        result.messageId(),
                        receivedAt,
                        result.timestamp());

                log.info("Notification sent: requestId={}, provider={}, messageId={}",
                        request.getRequestId(), provider.getProviderName(), result.messageId());
            } else {
                response = NotificationResponse.failed(
                        request,
                        provider.getProviderName(),
                        result.errorCode(),
                        result.errorMessage(),
                        receivedAt);

                log.warn("Notification failed: requestId={}, error={}: {}",
                        request.getRequestId(), result.errorCode(), result.errorMessage());
            }

            // Update audit
            auditService.updateStatus(request.getRequestId(), response.status(),
                    response.providerMessageId(), response.errorCode(), response.errorMessage());

            return response;

        } catch (NotificationException e) {
            log.error("Notification error: requestId={}, error={}: {}",
                    request.getRequestId(), e.getErrorCode(), e.getMessage());

            response = NotificationResponse.failed(
                    request,
                    null,
                    e.getErrorCode(),
                    e.getMessage(),
                    receivedAt);

            auditService.updateStatus(request.getRequestId(), response.status(),
                    null, response.errorCode(), response.errorMessage());

            return response;

        } catch (Exception e) {
            log.error("Unexpected error processing notification: requestId={}", request.getRequestId(), e);

            response = NotificationResponse.failed(
                    request,
                    null,
                    "INTERNAL_ERROR",
                    e.getMessage(),
                    receivedAt);

            auditService.updateStatus(request.getRequestId(), response.status(),
                    null, response.errorCode(), response.errorMessage());

            return response;

        } finally {
            // Always close the idempotency record. If 'response' is null we
            // got here via an unexpected throw outside the catch coverage —
            // build a synthetic FAILED response so the IN_PROGRESS lock
            // doesn't linger until TTL.
            if (idemKey != null) {
                NotificationResponse toRecord = response != null ? response
                        : NotificationResponse.failed(request, null,
                                "INTERNAL_ERROR", "Dispatch terminated without a response", receivedAt);
                idempotencyStore.get().markComplete(idemKey, toRecord);
            }
        }
    }

    /**
     * Build the {@link IdempotencyKey} for this request, or {@code null} if
     * idempotency is disabled (no store bean) or the caller didn't supply
     * an {@code idempotencyKey}.
     *
     * <p>The {@code callerId} component (DD-11) is read from
     * {@link NotificationRequest#getCallerId()} — the request enrichment
     * step has already pulled it from the {@code X-Service-Id} header via
     * {@link RequestContext} when the REST path is used. A {@code null}
     * value is permitted; in that case the dedup scope reduces to
     * {@code (tenantId, null, idempotencyKey)} which matches pre-DD-11
     * behaviour.
     */
    private IdempotencyKey idempotencyKeyFor(NotificationRequest request) {
        if (idempotencyStore.isEmpty() || !StringUtils.hasText(request.getIdempotencyKey())) {
            return null;
        }
        return new IdempotencyKey(request.getTenantId(), request.getCallerId(), request.getIdempotencyKey());
    }

    /**
     * Statuses that count as a successful prior outcome — replays for
     * these statuses return the cached response (HTTP 200). FAILED and
     * REJECTED fall through to fresh dispatch per DD-10 §Semantics.
     */
    private static boolean isReplayable(NotificationStatus status) {
        return status == NotificationStatus.SENT
                || status == NotificationStatus.DELIVERED
                || status == NotificationStatus.ACCEPTED;
    }

    @Override
    public CompletableFuture<NotificationResponse> sendAsync(NotificationRequest request) {
        return CompletableFuture.supplyAsync(() -> send(request), asyncExecutor);
    }

    @Override
    public List<NotificationResponse> sendBatch(List<NotificationRequest> requests) {
        List<NotificationResponse> responses = new ArrayList<>(requests.size());
        for (NotificationRequest request : requests) {
            responses.add(send(request));
        }
        return responses;
    }

    @Override
    public CompletableFuture<List<NotificationResponse>> sendBatchAsync(List<NotificationRequest> requests) {
        List<CompletableFuture<NotificationResponse>> futures = requests.stream()
                .map(this::sendAsync)
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .toList());
    }

    /**
     * Enrich request with default values.
     * Uses RequestContext from app-building-commons for requestId, tenant,
     * and caller id (DD-11).
     */
    private void enrichRequest(NotificationRequest request) {
        RequestContext context = RequestContext.current();

        // Generate request ID if not provided (use from RequestContext if available)
        if (!StringUtils.hasText(request.getRequestId())) {
            String contextRequestId = context.getRequestId();
            request.setRequestId(StringUtils.hasText(contextRequestId)
                    ? contextRequestId
                    : UUID.randomUUID().toString());
        }

        // Set tenant ID from context if not provided
        if (!StringUtils.hasText(request.getTenantId())) {
            String contextTenant = TenantContext.getTenantId();
            if (!StringUtils.hasText(contextTenant)) {
                contextTenant = context.getTenantCode();
            }
            request.setTenantId(StringUtils.hasText(contextTenant)
                    ? contextTenant
                    : properties.getDefaultTenant());
        }

        // Caller id (DD-11): body wins over header; if neither is set, leave null.
        // The TenantFilter stashes the X-Service-Id header value under
        // CALLER_ID_ATTRIBUTE on RequestContext (the constant lives in the
        // REST module, but core can't depend on it — read by literal name).
        if (!StringUtils.hasText(request.getCallerId())) {
            Object contextCaller = context.get(CALLER_ID_ATTRIBUTE);
            if (contextCaller instanceof String s && StringUtils.hasText(s)) {
                request.setCallerId(s);
            }
        }

        // Ensure template data is not null
        if (request.getTemplateData() == null) {
            request.setTemplateData(new java.util.HashMap<>());
        }
    }

    /**
     * RequestContext attribute key under which the resolved caller id is
     * stashed by the REST {@code TenantFilter}. Mirrors
     * {@code TenantFilter.CALLER_ID_ATTRIBUTE} — duplicated as a string
     * literal here to avoid a cyclic core → rest module dependency.
     */
    private static final String CALLER_ID_ATTRIBUTE = "notification.callerId";
}
