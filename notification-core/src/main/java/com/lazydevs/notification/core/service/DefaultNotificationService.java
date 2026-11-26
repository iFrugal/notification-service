package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.exception.NotificationException;
import com.lazydevs.notification.api.exception.RateLimitExceededException;
import com.lazydevs.notification.api.exception.TemplateRenderException;
import com.lazydevs.notification.api.guard.RecipientGuard;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyReleasePolicy;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationAudit;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.NotificationView;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.push.PushTargeting;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.api.util.MessageScrubber;
import com.lazydevs.notification.api.util.PiiMasking;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.idempotency.FailurePolicyReleasePolicy;
import com.lazydevs.notification.core.idempotency.RequestFingerprint;
import com.lazydevs.notification.core.metrics.NotificationMetrics;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.retry.RetryExecutor;
import com.lazydevs.notification.core.push.PushTargetExpansion;
import com.lazydevs.notification.core.template.ChannelContentRenderer;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import lazydevs.persistence.connection.multitenant.TenantContext;
import lazydevs.services.basic.filter.RequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Default implementation of NotificationService.
 */
@Slf4j
public class DefaultNotificationService implements NotificationService {

    private final NotificationProperties properties;
    private final ProviderRegistry providerRegistry;
    private final NotificationTemplateEngine templateEngine;
    private final NotificationAuditService auditService;
    private final Optional<IdempotencyCoordinator> idempotency;
    private final Optional<RateLimiter> rateLimiter;
    private final Optional<RetryExecutor> retryExecutor;
    private final Optional<DeadLetterStore> deadLetterStore;
    private final Optional<NotificationMetrics> metrics;
    private final MessageScrubber scrubber;
    private final NotificationViews views;
    private final RecipientGuard recipientGuard;
    private final Map<com.lazydevs.notification.api.Channel, ChannelContentRenderer> renderers;
    private final PushTargetExpansion pushTargets;
    private final Executor asyncExecutor;

    /**
     * The 1.2.0 constructor: {@link NotificationServiceExtensions#defaults()}.
     */
    @SuppressWarnings("java:S107") // one parameter per optional collaborator, see DD-10..DD-22
    public DefaultNotificationService(
            NotificationProperties properties,
            ProviderRegistry providerRegistry,
            NotificationTemplateEngine templateEngine,
            NotificationAuditService auditService,
            Optional<IdempotencyStore> idempotencyStore,
            Optional<RateLimiter> rateLimiter,
            Optional<RetryExecutor> retryExecutor,
            Optional<DeadLetterStore> deadLetterStore,
            Optional<NotificationMetrics> metrics) {
        this(properties, providerRegistry, templateEngine, auditService, idempotencyStore, rateLimiter,
                retryExecutor, deadLetterStore, metrics, NotificationServiceExtensions.defaults());
    }

    /**
     * @param extensions collaborators added since 1.2.1
     * @since 1.2.1
     */
    @SuppressWarnings("java:S107") // one parameter per optional collaborator, see DD-10..DD-22
    public DefaultNotificationService(
            NotificationProperties properties,
            ProviderRegistry providerRegistry,
            NotificationTemplateEngine templateEngine,
            NotificationAuditService auditService,
            Optional<IdempotencyStore> idempotencyStore,
            Optional<RateLimiter> rateLimiter,
            Optional<RetryExecutor> retryExecutor,
            Optional<DeadLetterStore> deadLetterStore,
            Optional<NotificationMetrics> metrics,
            NotificationServiceExtensions extensions) {
        this.properties = properties;
        this.providerRegistry = providerRegistry;
        this.templateEngine = templateEngine;
        this.auditService = auditService;
        // Optional<RateLimiter>: empty when notification.rate-limit.enabled=false
        // - same wiring shape as idempotency, see DD-12.
        this.rateLimiter = rateLimiter;
        // Optional<RetryExecutor> + Optional<DeadLetterStore> follow the
        // same opt-in pattern (DD-13). Empty Optionals: single-attempt
        // dispatch, no DLQ recording - backwards-compatible default.
        this.retryExecutor = retryExecutor;
        this.deadLetterStore = deadLetterStore;
        // Optional<NotificationMetrics>: empty when Micrometer isn't on
        // the classpath (DD-22). Send-path methods call metrics.ifPresent(...).
        this.metrics = metrics;
        this.scrubber = extensions.messageScrubber();
        this.views = new NotificationViews(extensions.deliveryEvents());
        this.recipientGuard = extensions.recipientGuard();
        this.renderers = renderersByChannel(extensions.renderers());
        this.pushTargets = extensions.pushTargets();
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
        // Optional<IdempotencyStore>: empty when notification.idempotency.enabled=false.
        IdempotencyReleasePolicy releasePolicy = extensions.releasePolicy() != null ? extensions.releasePolicy()
                : new FailurePolicyReleasePolicy(properties);
        this.idempotency = idempotencyStore.map(store -> new IdempotencyCoordinator(
                store, properties, auditService, metrics, scrubber, releasePolicy, extensions.clock()));
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

        // DD-27: with validate-before-dispatch on, a request whose template cannot
        // render is refused here, before anything is claimed, counted or stored.
        RenderedContent preRendered = renderBeforeDispatch(request);

        // ============== Idempotency check (DD-10, DD-32) ==============
        // Run BEFORE the main try/catch so IdempotencyInProgressException and
        // IdempotencyKeyReusedException (NotificationException subclasses)
        // propagate as 409 rather than being converted into a FAILED response.
        //
        // The rate-limit check (DD-12) is interleaved with this block:
        // * Replays of completed keys: return cached, NO token consumed.
        // * IN_PROGRESS conflicts: throw 409, NO token consumed.
        // * Fresh sends (about to mark-in-progress): DO consume a token, before
        //   the claim so a denial doesn't leave a phantom IN_PROGRESS slot.
        // See DD-12 "Why this ordering relative to idempotency".
        IdempotencyKey idemKey = idempotencyKeyFor(request);
        if (idemKey != null) {
            // The fingerprint is taken from the request as the caller sent it (DD-32).
            String fingerprint = RequestFingerprint.of(request);
            Optional<NotificationResponse> replay = idempotency.get()
                    .claim(request, idemKey, fingerprint, () -> checkRateLimit(request));
            if (replay.isPresent()) {
                return replay.get();
            }
        } else {
            // No idempotency key on this request: apply the rate limit
            // here so non-idempotent traffic still gets throttled.
            checkRateLimit(request);
        }

        // ============== Dispatch ==============
        // Held in 'response' for the finally block to close out the
        // idempotency record on every exit path (success or failure).
        NotificationResponse response = null;
        try {
            // Recipient guard (DD-34): decided on the request as the caller sent
            // it (the fingerprint and the claim above used that one too); the
            // audit record and the provider only ever see the guarded recipient.
            RecipientGuard.Decision guard = guard(request);
            NotificationRequest guarded = guard != null && guard.changed()
                    ? request.toBuilder().recipient(guard.recipient()).build()
                    : request;

            // Record audit (received)
            fillRecipientSummary(auditService.recordReceived(guarded), guarded);

            // Render content (already rendered when validate-before-dispatch is on, DD-27):
            // the channel's renderer when one is registered (DD-35), otherwise the
            // template; from the request as the caller sent it, exactly once per send.
            RenderedContent rendered = preRendered != null ? preRendered : render(request);
            if (guard != null && guard.blocked()) {
                throw new NotificationException(RecipientGuard.BLOCKED_ERROR_CODE,
                        "Every recipient was blocked by the recipient guard");
            }
            RenderedContent content = guard == null ? rendered
                    : withSubjectPrefix(rendered, guard.subjectPrefix(), guarded);

            // Push user targeting (DD-37): a recipient that names a user goes to the
            // user's active devices. Only the provider sees the tokens; the request the
            // caller sent, kept by idempotency, audit and the DLQ, still names the user.
            NotificationRequest dispatched = pushTargets.expand(guarded);

            // Get provider
            NotificationProvider provider = providerRegistry.getProvider(
                    request.getTenantId(),
                    request.getChannel(),
                    request.getProvider());

            // Send notification — wrapped in RetryExecutor when configured
            // (DD-13). The executor returns the final SendResult plus the
            // attempt count so we can record both. When the executor is
            // absent (notification.retry.enabled=false) we fall through
            // to a single attempt — same behaviour as pre-DD-13.
            SendResult result;
            int attempts;
            if (retryExecutor.isPresent()) {
                // DD-23: pass the channel so the executor can resolve
                // per-channel rule overrides (notification.retry.by-channel.<channel>).
                // Null-safe — execute(null, ...) falls back to the global rule.
                RetryExecutor.Outcome outcome = retryExecutor.get()
                        .execute(request.getChannel(), () -> provider.send(dispatched, content));
                result = outcome.result();
                attempts = outcome.attempts();
                if (attempts > 1) {
                    // Sanitize request-derived values before logging
                    // (CodeQL "log injection" rule) — requestId may be
                    // caller-supplied and could carry control chars.
                    log.info("Notification took {} attempt(s): requestId={}, provider={}, success={}",
                            attempts, sanitizeForLog(request.getRequestId()),
                            sanitizeForLog(provider.getProviderName()), result.success());
                    int totalAttempts = attempts;
                    metrics.ifPresent(m -> m.recordRetry(request.getChannel(), totalAttempts));
                }
            } else {
                result = provider.send(dispatched, content);
                attempts = 1;
            }

            // Build response
            if (result.success()) {
                response = NotificationResponse.sent(
                        request,
                        provider.getProviderName(),
                        result.messageId(),
                        receivedAt,
                        result.timestamp())
                        .withProviderMetadata(result.providerMetadata());

                log.info("Notification sent: requestId={}, provider={}, messageId={}",
                        request.getRequestId(), provider.getProviderName(), result.messageId());
            } else {
                // Keep the provider message id: a provider that identified the
                // send before it failed (ACS operation id) needs it for reconciliation.
                // The provider's error text can quote the recipient; it is
                // redacted before it reaches the caller, audit, DLQ and store.
                response = NotificationResponse.failed(
                        request,
                        provider.getProviderName(),
                        result.messageId(),
                        result.errorCode(),
                        PiiMasking.redact(result.errorMessage()),
                        receivedAt)
                        .withFailureType(failureTypeOf(result))
                        .withProviderMetadata(result.providerMetadata())
                        .withRetryAfter(result.retryAfter().orElse(null));

                if (result.failureType() == FailureType.AMBIGUOUS && attempts == 1) {
                    // The provider may have accepted the message, so it is not
                    // resent; the DLQ entry lets an operator reconcile first.
                    log.warn("Notification failed, not retried: ambiguous (the provider may have accepted it):"
                                    + " requestId={}, provider={}, messageId={}, error={}: {}",
                            request.getRequestId(), provider.getProviderName(), result.messageId(),
                            result.errorCode(), PiiMasking.redact(result.errorMessage()));
                } else {
                    log.warn("Notification failed: requestId={}, error={}: {}",
                            request.getRequestId(), result.errorCode(), PiiMasking.redact(result.errorMessage()));
                }

                // DD-13: push to DLQ when configured. We push regardless of
                // attempts taken — both "permanent failure on first try" and
                // "retries exhausted" land here. The entry's failureType +
                // attempts let operators distinguish them.
                recordDeadLetter(request, response, attempts, response.failureType());
            }

            recordAuditOutcome(response);

            recordSendMetric(request.getChannel(), response.status(), receivedAt);
            return response;

        } catch (NotificationException e) {
            log.error("Notification error: requestId={}, error={}: {}",
                    request.getRequestId(), e.getErrorCode(), PiiMasking.redact(e.getMessage()));

            response = NotificationResponse.failed(
                    request,
                    null,
                    e.getErrorCode(),
                    PiiMasking.redact(e.getMessage()),
                    receivedAt)
                    .withFailureType(FailureType.PERMANENT);

            recordAuditOutcome(response);

            // DD-13: configuration / template / provider-resolution
            // failures are PERMANENT — retrying won't help — but still
            // worth recording so operators see them in /admin/dead-letter.
            // A user without devices is not an operator problem and a replay
            // cannot succeed until the user registers one (DD-37), so it is not.
            if (!PushTargeting.NO_DEVICES.equals(e.getErrorCode())) {
                recordDeadLetter(request, response, 1, FailureType.PERMANENT);
            }
            recordSendMetric(request.getChannel(), response.status(), receivedAt);
            return response;

        } catch (Exception e) {
            log.error("Unexpected error processing notification: requestId={}", request.getRequestId(), e);

            response = NotificationResponse.failed(
                    request,
                    null,
                    "INTERNAL_ERROR",
                    PiiMasking.redact(e.getMessage()),
                    receivedAt)
                    .withFailureType(FailureType.UNKNOWN);

            recordAuditOutcome(response);

            // DD-13: unexpected throw — classify as UNKNOWN so operators
            // can triage from the DLQ admin endpoint.
            recordDeadLetter(request, response, 1, FailureType.UNKNOWN);
            recordSendMetric(request.getChannel(), response.status(), receivedAt);
            return response;

        } finally {
            // Always close the idempotency record. If 'response' is null we
            // got here via an unexpected throw outside the catch coverage —
            // build a synthetic FAILED response so the IN_PROGRESS lock
            // doesn't linger until TTL.
            if (idemKey != null) {
                NotificationResponse toRecord = response != null ? response
                        : NotificationResponse.failed(request, null,
                                "INTERNAL_ERROR", "Dispatch terminated without a response", receivedAt)
                                .withFailureType(FailureType.UNKNOWN);
                idempotency.get().close(request, idemKey, toRecord, this::refundUnsent);
            }
        }
    }

    private RenderedContent render(NotificationRequest request) {
        ChannelContentRenderer renderer = request.getChannel() == null ? null : renderers.get(request.getChannel());
        return renderer != null ? renderer.render(request) : templateEngine.render(request);
    }

    /**
     * Index the renderers by channel. Two renderers for one channel are a
     * wiring mistake that would silently pick one of them, so they fail the
     * startup, naming both classes.
     */
    private static Map<com.lazydevs.notification.api.Channel, ChannelContentRenderer> renderersByChannel(
            List<ChannelContentRenderer> renderers) {
        Map<com.lazydevs.notification.api.Channel, ChannelContentRenderer> byChannel =
                new EnumMap<>(com.lazydevs.notification.api.Channel.class);
        if (renderers == null) {
            return Collections.unmodifiableMap(byChannel);
        }
        for (ChannelContentRenderer renderer : renderers) {
            if (renderer == null) {
                continue;
            }
            com.lazydevs.notification.api.Channel channel = renderer.channel();
            if (channel == null) {
                throw new IllegalStateException("ChannelContentRenderer " + renderer.getClass().getName()
                        + " returned no channel");
            }
            ChannelContentRenderer existing = byChannel.putIfAbsent(channel, renderer);
            if (existing != null) {
                throw new IllegalStateException("Two ChannelContentRenderer beans for channel " + channel + ": "
                        + existing.getClass().getName() + " and " + renderer.getClass().getName()
                        + "; keep one of them");
            }
        }
        return Collections.unmodifiableMap(byChannel);
    }

    /**
     * Render and validate the template up front when
     * {@code notification.template.validate-before-dispatch} is on (DD-27), so a
     * {@link TemplateRenderException} reaches the caller with no idempotency claim, rate
     * token, audit row or DLQ entry. A channel with a {@link ChannelContentRenderer}
     * (DD-35) is rendered by it here, so its {@link TemplateRenderException}, for example
     * a WhatsApp parameter that breaks a rule ({@code PARAMETER_INVALID}), reaches the
     * caller the same way. {@code null} when the flag is off: the engine is not
     * touched and the dispatch path renders as in 1.2. A request without a channel also
     * gets {@code null}, so it fails on the dispatch path exactly as before.
     */
    private RenderedContent renderBeforeDispatch(NotificationRequest request) {
        // getTemplate() is never null in a bound configuration; tolerate it like a flag that is off
        NotificationProperties.TemplateProperties template = properties.getTemplate();
        if (template == null || !template.isValidateBeforeDispatch() || request.getChannel() == null) {
            return null;
        }
        try {
            ChannelContentRenderer renderer = renderers.get(request.getChannel());
            return renderer != null ? renderer.render(request) : templateEngine.renderAndValidate(request);
        } catch (TemplateRenderException e) {
            metrics.ifPresent(m -> m.recordTemplateValidationFailed(request.getChannel(), e.getReason()));
            throw e;
        }
    }

    /**
     * Run the recipient guard, logging one INFO line with counts (never the
     * addresses) when it changed anything. A request without a recipient is
     * not guarded ({@code null}); it is left to the provider's own validation.
     */
    private RecipientGuard.Decision guard(NotificationRequest request) {
        if (request.getRecipient() == null) {
            return null;
        }
        RecipientGuard.Decision decision = recipientGuard.check(request);
        if (decision.changed()) {
            log.info("Recipient guard changed the recipients: requestId={}, channel={}, redirected={}, dropped={},"
                            + " blocked={}", sanitizeForLog(request.getRequestId()), request.getChannel(),
                    decision.redirectedOriginals().size(), decision.dropped(), decision.blocked());
        }
        return decision;
    }

    /**
     * Put the guard's subject prefix in front of the email subject: the
     * rendered one, else the recipient's own.
     */
    private static RenderedContent withSubjectPrefix(RenderedContent content, String prefix,
                                                     NotificationRequest dispatched) {
        if (prefix == null || prefix.isEmpty() || content == null) {
            return content;
        }
        String subject = content.subject();
        if (!StringUtils.hasText(subject) && dispatched.getRecipient() instanceof EmailRecipient email) {
            subject = email.subject();
        }
        return new RenderedContent(prefix + (subject == null ? "" : subject),
                content.textBody(), content.htmlBody(), content.templateId(), content.payload());
    }

    /**
     * Give back the rate-limit token of a failed send whose idempotency key the
     * {@link IdempotencyReleasePolicy} released (DD-30, DD-32), so the
     * caller's retry under the key is not charged twice. Only with
     * {@code notification.rate-limit.refund-unsent}, and never for an
     * {@code AMBIGUOUS} or {@code UNKNOWN} failure, which the provider may have
     * accepted. The key is the one {@link #checkRateLimit} consumed from.
     */
    private void refundUnsent(NotificationRequest request, NotificationResponse failed) {
        NotificationProperties.RateLimitProperties rateLimit = properties.getRateLimit();
        if (rateLimiter.isEmpty() || rateLimit == null || !rateLimit.isRefundUnsent()
                || failed.failureType() == null
                || failed.failureType() == FailureType.AMBIGUOUS
                || failed.failureType() == FailureType.UNKNOWN) {
            return;
        }
        RateLimiter.RateLimitKey key = rateLimitKeyFor(request);
        try {
            rateLimiter.get().refund(key);
            log.debug("Rate-limit token refunded for an unsent request: requestId={}",
                    sanitizeForLog(request.getRequestId()));
        } catch (RuntimeException e) {
            log.warn("Rate-limit refund failed for requestId={}: {}",
                    sanitizeForLog(request.getRequestId()), e.toString());
        }
    }

    /** The provider's classification of a failed result; {@link FailureType#UNKNOWN} when it gave none. */
    private static FailureType failureTypeOf(SendResult result) {
        return result.failureType() != null ? result.failureType() : FailureType.UNKNOWN;
    }

    /**
     * Hand the outcome to the audit service with its error message scrubbed
     * (DD-32): the audit record backs the status view and is persisted.
     */
    private void recordAuditOutcome(NotificationResponse response) {
        auditService.recordOutcome(response.errorMessage() == null ? response
                : response.withErrorMessage(scrubber.scrub(response.errorMessage())));
    }

    /**
     * Fill {@link NotificationAudit#getRecipientSummary()} with the masked
     * recipient when the audit implementation left it empty, so the record
     * never needs the raw address or number (DD-07).
     */
    private static void fillRecipientSummary(NotificationAudit audit, NotificationRequest request) {
        if (audit != null && !StringUtils.hasText(audit.getRecipientSummary())) {
            audit.setRecipientSummary(PiiMasking.mask(request.getRecipient()));
        }
    }

    /**
     * Push a failed notification to the DLQ if configured (DD-13). The
     * store contract is "never throw" — wrap defensively just in case so
     * a flaky DLQ doesn't double-fault the caller's already-failing
     * dispatch path.
     */
    private void recordDeadLetter(NotificationRequest request,
                                  NotificationResponse response,
                                  int attempts,
                                  FailureType failureType) {
        if (deadLetterStore.isEmpty()) {
            return;
        }
        try {
            deadLetterStore.get().add(new DeadLetterEntry(
                    Instant.now(), request, response, attempts, failureType));
            metrics.ifPresent(m -> m.recordDlqAdded(request.getChannel(), failureType));
        } catch (RuntimeException e) {
            // Same sanitization rationale as above — requestId may
            // carry caller-supplied control chars (CodeQL log injection).
            log.warn("DLQ record failed for requestId={}: {}",
                    sanitizeForLog(request.getRequestId()), e.toString());
        }
    }

    /**
     * Record the DD-22 send counter + timer at every return path
     * from {@link #send(NotificationRequest)}. Pulled out as a helper
     * so the three return sites (success, NotificationException,
     * generic catch) all emit the metric uniformly.
     */
    private void recordSendMetric(com.lazydevs.notification.api.Channel channel,
                                  NotificationStatus status,
                                  Instant receivedAt) {
        metrics.ifPresent(m -> m.recordSend(channel, status,
                java.time.Duration.between(receivedAt, Instant.now())));
    }

    /**
     * Apply the DD-12 rate limit if configured. No-op when the limiter
     * bean is absent (i.e. {@code notification.rate-limit.enabled=false}).
     * Throws {@link RateLimitExceededException} on denial — caller-side
     * try/catch is the controller advice that converts to HTTP 429.
     *
     * <p>Treats blank or null {@code callerId} uniformly as
     * {@code "anonymous"} so unidentified traffic shares one bucket per
     * tenant+channel rather than carving slots for empty-string callers
     * — matters when an attacker probes by sending a literal empty
     * header value.
     */
    private void checkRateLimit(NotificationRequest request) {
        if (rateLimiter.isEmpty()) {
            return;
        }
        RateLimiter.RateLimitKey rlKey = rateLimitKeyFor(request);
        RateLimiter.Decision decision = rateLimiter.get().tryConsume(rlKey);
        if (!decision.allowed()) {
            metrics.ifPresent(m -> m.recordRateLimitDenied(request.getChannel()));
            throw new RateLimitExceededException(
                    rlKey.tenantId(), rlKey.callerId(), rlKey.channel(),
                    decision.retryAfter());
        }
    }

    private static RateLimiter.RateLimitKey rateLimitKeyFor(NotificationRequest request) {
        String channel = request.getChannel() != null
                ? request.getChannel().name().toLowerCase()
                : "unknown";
        String callerForBucket = StringUtils.hasText(request.getCallerId())
                ? request.getCallerId()
                : "anonymous";
        return new RateLimiter.RateLimitKey(request.getTenantId(), callerForBucket, channel);
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
        if (idempotency.isEmpty() || !StringUtils.hasText(request.getIdempotencyKey())) {
            return null;
        }
        return new IdempotencyKey(request.getTenantId(), request.getCallerId(), request.getIdempotencyKey());
    }

    /**
     * Status view over the audit record (DD-33). Empty when audit is off.
     */
    @Override
    public Optional<NotificationView> find(String tenantId, String requestId) {
        if (!StringUtils.hasText(requestId)) {
            return Optional.empty();
        }
        return auditService.findByRequestId(tenantOrDefault(tenantId), requestId).map(views::toView);
    }

    /**
     * Status view of the latest request under an idempotency key (DD-33).
     * Empty when audit is off.
     */
    @Override
    public Optional<NotificationView> findByIdempotencyKey(String tenantId, String callerId, String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return Optional.empty();
        }
        return auditService.findByIdempotencyKey(tenantOrDefault(tenantId),
                StringUtils.hasText(callerId) ? callerId : null, idempotencyKey).map(views::toView);
    }

    private String tenantOrDefault(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId : properties.getDefaultTenant();
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

    /**
     * Replace ASCII control characters and line breaks in a value before it
     * goes into a log line - defense against log-injection through caller-supplied
     * fields like {@code requestId} and provider-supplied
     * {@code providerName}. Also sanitizes {@code null} to a literal
     * {@code "null"} so the log line stays well-formed even when a
     * caller leaves a field empty.
     */
    private static String sanitizeForLog(String s) {
        return s == null ? "null" : s.replaceAll("[\\p{Cntrl}]", "_").replaceAll("\\R", "_");
    }
}
