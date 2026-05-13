package com.lazydevs.notification.rest.controller;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventStore;
import com.lazydevs.notification.api.model.NotificationAudit;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.caller.CallerRegistry;
import com.lazydevs.notification.core.service.NotificationAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.ChannelConfig;
import com.lazydevs.notification.core.config.NotificationProperties.ProviderConfig;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitOverride;
import com.lazydevs.notification.core.config.NotificationProperties.RateLimitProperties;
import com.lazydevs.notification.core.config.NotificationProperties.TenantConfig;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.ratelimit.Bucket4jRateLimiter;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST controller for admin/configuration endpoints.
 */
@Slf4j
@RestController
@RequestMapping("${notification.rest.base-path:/api/v1}/admin")
@ConditionalOnProperty(prefix = "notification.rest", name = "enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "Admin",
        description = "Operator-facing introspection endpoints — current "
                + "tenant configuration, caller registry state, rate-limit "
                + "buckets, dead-letter snapshot, and template-cache "
                + "controls. Sensitive config values are masked.")
public class AdminController {

    /**
     * JSON field names used across admin responses. Pulled out as
     * constants so a typo in one place doesn't ship with an inconsistent
     * envelope, and to satisfy Sonar's "duplicated literal" rule.
     */
    private static final String FIELD_CHANNEL = "channel";
    /** JSON field name for human-readable status / explanation strings. */
    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_ENABLED = "enabled";
    private static final String FIELD_ENTRIES = "entries";
    private static final String FIELD_ORIGINAL_REQUEST_ID = "originalRequestId";
    private static final String FIELD_REPLAY_OF = "replayOf";
    private static final String FIELD_TENANT_ID = "tenantId";
    private static final String FIELD_CALLER_ID = "callerId";
    private static final String FIELD_ERROR_CODE = "errorCode";
    private static final String FIELD_ERROR_MESSAGE = "errorMessage";
    private static final String FIELD_REMOVED_FROM_DLQ = "removedFromDlq";
    private static final String FIELD_REQUEST_ID = "requestId";
    private static final String FIELD_PROVIDER_MESSAGE_ID = "providerMessageId";
    private static final String FIELD_IS_DEFAULT = "isDefault";

    /** Standard "DLQ disabled" explanation surfaced in 503 responses. */
    private static final String MSG_DLQ_DISABLED =
            "Dead-letter store is disabled. Enable with notification.dead-letter.enabled=true.";
    /** Snapshot-unavailable explanation for backends that can't iterate cheaply. */
    private static final String MSG_BACKEND_NO_SNAPSHOT =
            "Backing store does not support snapshot iteration; query the store directly.";

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "secret", "token", "api-key", "apikey", "auth-token", "authtoken",
            "credentials", "private-key", "privatekey", "account-sid", "accountsid",
            "access-key", "accesskey", "secret-key", "secretkey"
    );

    private final NotificationProperties properties;
    private final ProviderRegistry providerRegistry;
    private final NotificationTemplateEngine templateEngine;
    private final CallerRegistry callerRegistry;
    private final java.util.Optional<RateLimiter> rateLimiter;
    private final java.util.Optional<DeadLetterStore> deadLetterStore;
    private final java.util.Optional<DeliveryEventStore> deliveryEventStore;
    private final NotificationService notificationService;
    private final NotificationAuditService auditService;

    public AdminController(NotificationProperties properties,
                           ProviderRegistry providerRegistry,
                           NotificationTemplateEngine templateEngine,
                           CallerRegistry callerRegistry,
                           java.util.Optional<RateLimiter> rateLimiter,
                           java.util.Optional<DeadLetterStore> deadLetterStore,
                           java.util.Optional<DeliveryEventStore> deliveryEventStore,
                           NotificationService notificationService,
                           NotificationAuditService auditService) {
        this.properties = properties;
        this.providerRegistry = providerRegistry;
        this.templateEngine = templateEngine;
        this.callerRegistry = callerRegistry;
        this.rateLimiter = rateLimiter;
        this.deadLetterStore = deadLetterStore;
        this.deliveryEventStore = deliveryEventStore;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    /**
     * Get full configuration for all tenants.
     */
    @GetMapping("/configuration")
    public ResponseEntity<Map<String, Object>> getConfiguration() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("defaultTenant", properties.getDefaultTenant());
        result.put("tenants", buildTenantsConfig());
        return ResponseEntity.ok(result);
    }

    /**
     * Get configuration for a specific tenant.
     */
    @GetMapping("/configuration/tenants/{tenantId}")
    public ResponseEntity<Map<String, Object>> getTenantConfiguration(@PathVariable String tenantId) {
        TenantConfig tenantConfig = properties.getTenants().get(tenantId);
        if (tenantConfig == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(buildTenantConfig(tenantConfig));
    }

    /**
     * Get configuration for a specific channel in a tenant.
     */
    @GetMapping("/configuration/tenants/{tenantId}/channels/{channel}")
    public ResponseEntity<Map<String, Object>> getChannelConfiguration(
            @PathVariable String tenantId,
            @PathVariable String channel) {

        TenantConfig tenantConfig = properties.getTenants().get(tenantId);
        if (tenantConfig == null) {
            return ResponseEntity.notFound().build();
        }

        ChannelConfig channelConfig = tenantConfig.getChannels().get(channel.toLowerCase());
        if (channelConfig == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(buildChannelConfig(channelConfig));
    }

    /**
     * Health check with provider status.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Overall status
        boolean allHealthy = true;
        Map<String, String> providerStatus = new LinkedHashMap<>();

        for (Map.Entry<String, NotificationProvider> entry : providerRegistry.getAllProviders().entrySet()) {
            String key = entry.getKey();
            NotificationProvider provider = entry.getValue();
            boolean healthy = provider.isHealthy();
            providerStatus.put(key, healthy ? "HEALTHY" : "UNHEALTHY");
            if (!healthy) {
                allHealthy = false;
            }
        }

        result.put(FIELD_STATUS, allHealthy ? "UP" : "DEGRADED");
        result.put("providers", providerStatus);

        // Kafka status (if enabled)
        if (properties.getKafka().isEnabled()) {
            result.put("kafka", Map.of(
                    FIELD_ENABLED, true,
                    "topic", properties.getKafka().getTopic(),
                    "groupId", properties.getKafka().getGroupId()
            ));
        }

        // Audit status
        result.put("audit", Map.of(FIELD_ENABLED, properties.getAudit().isEnabled()));

        return ResponseEntity.ok(result);
    }

    /**
     * Caller-registry state (DD-11). Mirrors the configuration the
     * registry was initialised with — useful during rollout to confirm the
     * deployed pod sees the expected list and mode.
     */
    @GetMapping("/caller-registry")
    @Operation(summary = "Caller-registry state (DD-11)",
            description = "Whether the registry is enabled, whether strict-mode "
                    + "rejection is on, and the configured `known-services` list.")
    public ResponseEntity<Map<String, Object>> getCallerRegistry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(FIELD_ENABLED, callerRegistry.isEnabled());
        result.put("strict", callerRegistry.isStrict());
        // Sorted-by-config-order set; Set.copyOf on a LinkedHashSet
        // preserves the iteration order, so ArrayList.sort is unnecessary.
        result.put("knownServices", new java.util.ArrayList<>(callerRegistry.getKnownServices()));
        return ResponseEntity.ok(result);
    }

    /**
     * Rate-limit configuration + live bucket snapshot (DD-12). Returns
     * the configured default rule, all overrides, and (when the in-memory
     * Bucket4j impl is active) the per-bucket available-token counts.
     */
    @GetMapping("/rate-limit")
    @Operation(summary = "Rate-limit configuration + live snapshot (DD-12)",
            description = "Returns the configured default rule, all overrides "
                    + "(in match-precedence order), and — when the in-memory "
                    + "Bucket4j impl is active — a snapshot of currently-tracked "
                    + "buckets with available-token counts.")
    public ResponseEntity<Map<String, Object>> getRateLimit() {
        Map<String, Object> result = new LinkedHashMap<>();
        RateLimitProperties cfg = properties.getRateLimit();
        result.put(FIELD_ENABLED, cfg.isEnabled());
        result.put("default", ruleAsMap(cfg.getDefaultRule()));

        java.util.List<Map<String, Object>> overrides = new java.util.ArrayList<>();
        for (RateLimitOverride o : cfg.getOverrides()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("tenant", o.getTenant());
            if (o.getCaller() != null) entry.put("caller", o.getCaller());
            if (o.getChannel() != null) entry.put(FIELD_CHANNEL, o.getChannel());
            entry.put("capacity", o.getCapacity());
            entry.put("refillTokens", o.getRefillTokens());
            entry.put("refillPeriod", o.getRefillPeriod().toString());
            overrides.add(entry);
        }
        result.put("overrides", overrides);

        // Live snapshot — only available when the default Bucket4j impl is
        // wired (a future Redis impl would expose this differently or not
        // at all).
        if (rateLimiter.isPresent() && rateLimiter.get() instanceof Bucket4jRateLimiter b4j) {
            java.util.List<Map<String, Object>> active = new java.util.ArrayList<>();
            b4j.snapshot().forEach((key, tokens) -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("tenant", key.tenantId());
                e.put("caller", key.callerId());
                e.put(FIELD_CHANNEL, key.channel());
                e.put("availableTokens", tokens);
                active.add(e);
            });
            result.put("activeBuckets", active);
        }
        return ResponseEntity.ok(result);
    }

    private static Map<String, Object> ruleAsMap(NotificationProperties.RateLimitRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("capacity", r.getCapacity());
        m.put("refillTokens", r.getRefillTokens());
        m.put("refillPeriod", r.getRefillPeriod().toString());
        return m;
    }

    /**
     * Dead-letter store snapshot (DD-13). Returns the configured cap +
     * a summary of recent entries (most recent first).
     *
     * <p>Intentionally <strong>does not</strong> include the full
     * {@code NotificationRequest} payload — template data may carry
     * PII. Operators get the routing identifiers (tenant, caller,
     * channel, requestId) plus the failure detail; full payload access
     * is on a future replay endpoint with proper auth.
     *
     * <p>Returns {@code 503 Service Unavailable} when the DLQ bean is
     * absent ({@code notification.dead-letter.enabled=false}) — the
     * endpoint is meaningfully disabled, not just empty.
     */
    @GetMapping("/dead-letter")
    @Operation(summary = "Dead-letter snapshot (DD-13)",
            description = "Recent retry-exhausted and permanent-failure entries, "
                    + "most recent first. Returns `503` when the DLQ is disabled "
                    + "(`notification.dead-letter.enabled=false`). Request payload "
                    + "is intentionally omitted from the response — template data "
                    + "may carry PII; full payload access is on a future replay "
                    + "endpoint with proper auth.")
    public ResponseEntity<Map<String, Object>> getDeadLetter(
            @RequestParam(defaultValue = "100") int limit) {
        if (deadLetterStore.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put(FIELD_ENABLED, false);
            body.put(FIELD_MESSAGE, MSG_DLQ_DISABLED);
            return ResponseEntity.status(503).body(body);
        }
        DeadLetterStore store = deadLetterStore.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(FIELD_ENABLED, true);
        result.put("maxEntries", properties.getDeadLetter().getMaxEntries());
        result.put("size", store.size());

        // Snapshot returns Optional.empty() for backends that can't
        // iterate cheaply (e.g. a future Redis-backed DLQ with 100k
        // entries); the in-memory default always returns a list.
        java.util.Optional<java.util.List<DeadLetterEntry>> snapshot = store.snapshot();
        if (snapshot.isPresent()) {
            // Math.clamp arrived in Java 21 — clearer than the
            // Math.max(min, Math.min(max, x)) idiom and Sonar prefers it.
            int safeLimit = Math.clamp(limit, 1, 1000);
            result.put(FIELD_ENTRIES, snapshot.get().stream()
                    .limit(safeLimit)
                    .map(this::toAdminEntry)
                    .toList());
        } else {
            result.put(FIELD_ENTRIES, null);
            result.put(FIELD_MESSAGE, MSG_BACKEND_NO_SNAPSHOT);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Replay a dead-lettered notification (DD-15). Looks up the entry
     * by its original {@code requestId}, builds a fresh
     * {@link NotificationRequest} from the captured payload (new
     * {@code requestId}, fresh {@code idempotencyKey}, {@code replayOf}
     * pointing at the original), and re-submits it through
     * {@link NotificationService#send(NotificationRequest)}.
     *
     * <p>Lifecycle:
     * <ul>
     *   <li>Successful replay (non-{@code FAILED}) — original entry is
     *       removed from the DLQ. The new send may produce its own DLQ
     *       entry if it ends up failing further down the line, but
     *       that's a fresh record with its own {@code replayOf}.</li>
     *   <li>Replay failure (still {@code FAILED}) — original entry stays
     *       in the DLQ; HTTP 502 surfaces the new error to the operator
     *       so the failure is loud rather than silent.</li>
     * </ul>
     *
     * <p>The requesting tenant is taken from the path-resolved entry
     * (not from {@code X-Tenant-Id}) — admin operators replay across
     * tenants. RequestIds are tenant-unique by DD-13 §audit semantics
     * but not globally unique; the lookup is therefore tenant-scoped to
     * prevent cross-tenant replay collisions.
     */
    @PostMapping("/dead-letter/{requestId}/replay")
    @Operation(summary = "Replay a dead-lettered notification (DD-15)",
            description = "Re-submit a dead-lettered request by its original "
                    + "requestId. The replay gets a fresh requestId + idempotencyKey "
                    + "and a `replayOf` field pointing at the original. On success "
                    + "the original entry is removed from the DLQ; on failure it "
                    + "stays and the response is 502.")
    public ResponseEntity<Map<String, Object>> replayDeadLetter(
            @PathVariable("requestId") String requestId,
            @RequestParam(name = "tenantId", required = false) String tenantId) {
        if (deadLetterStore.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put(FIELD_ENABLED, false);
            body.put(FIELD_MESSAGE, MSG_DLQ_DISABLED);
            return ResponseEntity.status(503).body(body);
        }
        if (requestId == null || requestId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of(FIELD_MESSAGE, "requestId is required"));
        }

        // Tenant defaults to the configured default-tenant when omitted —
        // matches the rest of the admin surface.
        String resolvedTenantId = (tenantId == null || tenantId.isBlank())
                ? properties.getDefaultTenant()
                : tenantId;

        DeadLetterStore store = deadLetterStore.get();
        Optional<DeadLetterEntry> opt = store.findByRequestId(resolvedTenantId, requestId);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(Map.of(FIELD_MESSAGE,
                            "No dead-letter entry for tenant=" + sanitizeForLog(resolvedTenantId)
                                    + ", requestId=" + sanitizeForLog(requestId)));
        }

        DeadLetterEntry entry = opt.get();
        NotificationRequest replay = buildReplayRequest(entry);

        NotificationResponse response;
        try {
            response = notificationService.send(replay);
        } catch (RuntimeException e) {
            log.error("Replay failed for tenant={}, requestId={}: {}",
                    sanitizeForLog(resolvedTenantId), sanitizeForLog(requestId), e.toString());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put(FIELD_ORIGINAL_REQUEST_ID, requestId);
            body.put(FIELD_REPLAY_OF, requestId);
            body.put(FIELD_MESSAGE, "Replay errored before reaching provider; entry kept.");
            return ResponseEntity.status(500).body(body);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_ORIGINAL_REQUEST_ID, requestId);
        body.put("newRequestId", response.requestId());
        body.put(FIELD_REPLAY_OF, requestId);
        body.put(FIELD_TENANT_ID, entry.request().getTenantId());
        body.put(FIELD_CALLER_ID, entry.request().getCallerId());
        body.put(FIELD_CHANNEL, entry.request().getChannel() != null
                ? entry.request().getChannel().name() : null);
        body.put(FIELD_STATUS, response.status().name());

        if (response.status() == NotificationStatus.FAILED
                || response.status() == NotificationStatus.REJECTED) {
            // Replay reached the provider but the provider failed again —
            // entry stays. 502 makes "this didn't work" loud in operator
            // tooling rather than buried in a 200 body.
            body.put(FIELD_ERROR_CODE, response.errorCode());
            body.put(FIELD_ERROR_MESSAGE, response.errorMessage());
            body.put(FIELD_MESSAGE, "Replay failed; entry kept in DLQ.");
            return ResponseEntity.status(502).body(body);
        }

        // Successful replay → drop the original from the DLQ. remove()
        // is documented to never throw and to return false on a missing
        // entry, so the worst case is a stale entry remains visible
        // until size pressure or a subsequent replay attempt.
        boolean removed = store.remove(resolvedTenantId, requestId);
        body.put(FIELD_REMOVED_FROM_DLQ, removed);
        body.put(FIELD_MESSAGE, "Replay submitted; entry removed from DLQ on successful send.");
        return ResponseEntity.ok(body);
    }

    /**
     * Bulk DLQ replay (DD-19). One operator action that revisits every
     * matching DLQ entry — most-recent-first, up to {@code limit} —
     * either as a dry-run preview or live execution.
     *
     * <p>Mandatory {@code tenantId} (blast-radius safety: a typo
     * scoped to one tenant is recoverable; the same typo without a
     * scope would touch the entire DLQ).
     *
     * <p>Live mode: per entry, builds a fresh request the same way
     * {@link #buildReplayRequest(DeadLetterEntry)} does, calls
     * {@link NotificationService#send(NotificationRequest)}, removes
     * the entry on success, leaves it on failure. The HTTP response
     * is always {@code 200} — per-entry failures appear in the
     * {@code entries} array so an operator running this for recovery
     * sees every individual outcome rather than a single status code.
     *
     * <p>Dry-run: returns the same preview list but skips both the
     * {@code send} and the {@code remove} — useful before pulling
     * the trigger on a 1000-entry recovery.
     */
    @PostMapping("/dead-letter/replay-batch")
    @Operation(summary = "Bulk DLQ replay (DD-19)",
            description = "Replay many DLQ entries in one call. Mandatory "
                    + "tenantId for blast-radius safety. limit defaults to 100, "
                    + "capped at 1000. dryRun=true returns the preview without "
                    + "side effects. Per-entry results in the response — HTTP "
                    + "200 even when some entries fail.")
    public ResponseEntity<Map<String, Object>> replayDeadLetterBatch(
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun) {

        if (deadLetterStore.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put(FIELD_ENABLED, false);
            body.put(FIELD_MESSAGE, MSG_DLQ_DISABLED);
            return ResponseEntity.status(503).body(body);
        }
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of(FIELD_MESSAGE, "tenantId is required"));
        }

        DeadLetterStore store = deadLetterStore.get();
        Optional<List<DeadLetterEntry>> snapshotOpt = store.snapshot();
        if (snapshotOpt.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", dryRun ? "dry-run" : "live");
            body.put(FIELD_TENANT_ID, tenantId);
            body.put(FIELD_MESSAGE,
                    "Backing store does not support snapshot iteration; "
                            + "use single-entry replay against known request ids.");
            return ResponseEntity.ok(body);
        }

        int safeLimit = Math.clamp(limit, 1, 1_000);
        List<DeadLetterEntry> matching = snapshotOpt.get().stream()
                .filter(e -> tenantId.equals(e.request().getTenantId()))
                .limit(safeLimit)
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", dryRun ? "dry-run" : "live");
        body.put(FIELD_TENANT_ID, tenantId);
        body.put("requested", matching.size());

        if (dryRun) {
            body.put(FIELD_ENTRIES, matching.stream()
                    .map(this::toDryRunPreviewEntry)
                    .toList());
            body.put(FIELD_MESSAGE,
                    "Dry-run only — no replays were submitted, no DLQ entries removed.");
            return ResponseEntity.ok(body);
        }

        int replayed = 0;
        int stillDeadLettered = 0;
        List<Map<String, Object>> resultEntries = new ArrayList<>(matching.size());
        for (DeadLetterEntry entry : matching) {
            Map<String, Object> row = new LinkedHashMap<>();
            String originalRequestId = entry.request().getRequestId();
            row.put(FIELD_ORIGINAL_REQUEST_ID, originalRequestId);

            NotificationRequest replay = buildReplayRequest(entry);
            try {
                NotificationResponse response = notificationService.send(replay);
                row.put("newRequestId", response.requestId());
                row.put(FIELD_STATUS, response.status().name());
                boolean succeeded = response.status() != NotificationStatus.FAILED
                        && response.status() != NotificationStatus.REJECTED;
                if (succeeded) {
                    boolean removed = store.remove(tenantId, originalRequestId);
                    row.put(FIELD_REMOVED_FROM_DLQ, removed);
                    replayed++;
                } else {
                    row.put(FIELD_ERROR_CODE, response.errorCode());
                    row.put(FIELD_ERROR_MESSAGE, response.errorMessage());
                    row.put(FIELD_REMOVED_FROM_DLQ, false);
                    stillDeadLettered++;
                }
            } catch (RuntimeException e) {
                // Per-entry failures don't fail the batch — caller sees
                // each row's outcome via the entries array.
                row.put(FIELD_STATUS, "FAILED");
                row.put(FIELD_ERROR_MESSAGE, e.getClass().getSimpleName() + ": " + e.getMessage());
                row.put(FIELD_REMOVED_FROM_DLQ, false);
                stillDeadLettered++;
                log.warn("Bulk replay entry failed [tenant={}, requestId={}]: {}",
                        sanitizeForLog(tenantId), sanitizeForLog(originalRequestId), e.toString());
            }
            resultEntries.add(row);
        }

        body.put("replayed", replayed);
        body.put("stillDeadLettered", stillDeadLettered);
        body.put(FIELD_ENTRIES, resultEntries);
        body.put(FIELD_MESSAGE, "Bulk replay completed. Successful entries removed from DLQ; "
                + "failed entries kept for inspection.");
        return ResponseEntity.ok(body);
    }

    /**
     * Build the dry-run preview row for a DLQ entry — same routing
     * identifiers as {@link #toAdminEntry(DeadLetterEntry)} but
     * keyed under {@code originalRequestId} to match the live-mode
     * response shape.
     */
    private Map<String, Object> toDryRunPreviewEntry(DeadLetterEntry entry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(FIELD_ORIGINAL_REQUEST_ID, entry.request().getRequestId());
        m.put(FIELD_CALLER_ID, entry.request().getCallerId());
        m.put(FIELD_CHANNEL, entry.request().getChannel() != null
                ? entry.request().getChannel().name() : null);
        m.put("failureType", entry.failureType().name());
        m.put("attempts", entry.attempts());
        return m;
    }

    /**
     * Build a fresh {@link NotificationRequest} from a DLQ entry, with
     * a new request id + idempotency key and {@code replayOf} pointing
     * at the original. The captured payload's mutable fields (template
     * data, attachments, recipient) are reused as-is — the original
     * intent of the send is what we want to replay, not a redacted
     * version of it.
     */
    private NotificationRequest buildReplayRequest(DeadLetterEntry entry) {
        NotificationRequest original = entry.request();
        String newRequestId = UUID.randomUUID().toString();
        return NotificationRequest.builder()
                .requestId(newRequestId)
                .correlationId(original.getCorrelationId())
                .tenantId(original.getTenantId())
                // Fresh idempotency key tied to the new request id so DD-10's
                // dedup window doesn't short-circuit straight to the cached
                // FAILED response from the original send.
                .idempotencyKey("replay-" + newRequestId)
                .callerId(original.getCallerId())
                .replayOf(original.getRequestId())
                .notificationType(original.getNotificationType())
                .channel(original.getChannel())
                .provider(original.getProvider())
                .recipient(original.getRecipient())
                .templateData(original.getTemplateData())
                .templateId(original.getTemplateId())
                .metadata(original.getMetadata())
                .priority(original.getPriority())
                .scheduledAt(null) // Replays are immediate by definition
                .attachments(original.getAttachments())
                .build();
    }

    /** Strip ASCII control characters from a value before logging. */
    private static String sanitizeForLog(String s) {
        return s == null ? "null" : s.replaceAll("[\\p{Cntrl}]", "_");
    }

    /**
     * Map a {@link DeadLetterEntry} to the admin-endpoint shape — no
     * template payload, no recipient detail (which may carry PII), just
     * the routing identifiers and failure summary.
     */
    private Map<String, Object> toAdminEntry(DeadLetterEntry entry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", entry.timestamp().toString());
        m.put(FIELD_TENANT_ID, entry.request().getTenantId());
        m.put(FIELD_CALLER_ID, entry.request().getCallerId());
        m.put(FIELD_CHANNEL, entry.request().getChannel() != null
                ? entry.request().getChannel().name() : null);
        m.put(FIELD_REQUEST_ID, entry.request().getRequestId());
        m.put("attempts", entry.attempts());
        m.put("failureType", entry.failureType().name());
        m.put(FIELD_ERROR_CODE, entry.response().errorCode());
        m.put(FIELD_ERROR_MESSAGE, entry.response().errorMessage());
        return m;
    }

    /**
     * Delivery event store snapshot (DD-17). Returns recent provider
     * callbacks (Twilio status, SES Delivery/Bounce/Complaint, etc.)
     * for operator inspection.
     *
     * <p>Filterable by {@code providerName} + {@code providerMessageId}
     * for "what happened to this specific notification?" lookups.
     *
     * <p>Returns {@code 503 Service Unavailable} when the store is
     * disabled ({@code notification.delivery-events.enabled=false}) —
     * the endpoint is meaningfully disabled, not just empty.
     *
     * <p>The raw provider attribute map is excluded from the response
     * by default — it can carry recipient identifiers (phone numbers,
     * email addresses) that operators don't always want surfaced in
     * admin tooling. Opt in with {@code ?includeRaw=true}.
     */
    @GetMapping("/delivery-events")
    @Operation(summary = "Delivery event snapshot (DD-17 / DD-18)",
            description = "Recent provider callbacks (delivery, bounce, "
                    + "complaint, undelivered) for operator inspection. "
                    + "Filter by `?requestId` (walks audit → "
                    + "providerMessageId, DD-18), or directly by "
                    + "`?providerName` + `?providerMessageId`. "
                    + "Returns 503 when "
                    + "`notification.delivery-events.enabled=false`. "
                    + "Raw provider attributes are excluded by default; "
                    + "set ?includeRaw=true to include them.")
    public ResponseEntity<Map<String, Object>> getDeliveryEvents(
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "requestId", required = false) String requestId,
            @RequestParam(name = "providerName", required = false) String providerName,
            @RequestParam(name = "providerMessageId", required = false) String providerMessageId,
            @RequestParam(name = "includeRaw", defaultValue = "false") boolean includeRaw) {

        if (deliveryEventStore.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put(FIELD_ENABLED, false);
            body.put(FIELD_MESSAGE, "Delivery event store is disabled. "
                    + "Enable with notification.delivery-events.enabled=true.");
            return ResponseEntity.status(503).body(body);
        }

        DeliveryEventStore store = deliveryEventStore.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(FIELD_ENABLED, true);
        result.put("maxEntries", properties.getDeliveryEvents().getMaxEntries());
        result.put("size", store.size());

        // Filter precedence (DD-18 §"Filter precedence"):
        //   1. requestId → walk audit, then events
        //   2. providerName + providerMessageId → direct lookup
        //   3. neither → snapshot
        boolean byRequestId = requestId != null && !requestId.isBlank();
        boolean byProviderTuple = providerName != null && !providerName.isBlank()
                && providerMessageId != null && !providerMessageId.isBlank();

        if (byRequestId) {
            return getDeliveryEventsByRequestId(requestId, limit, includeRaw, result, store);
        }

        java.util.Optional<java.util.List<DeliveryEvent>> source;
        if (byProviderTuple) {
            source = store.findByProviderMessageId(providerName, providerMessageId);
        } else {
            source = store.snapshot();
        }

        if (source.isPresent()) {
            int safeLimit = Math.clamp(limit, 1, 1_000);
            result.put(FIELD_ENTRIES, source.get().stream()
                    .limit(safeLimit)
                    .map(e -> toAdminEntry(e, includeRaw))
                    .toList());
        } else {
            result.put(FIELD_ENTRIES, null);
            result.put(FIELD_MESSAGE, MSG_BACKEND_NO_SNAPSHOT);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * DD-18 audit ↔ delivery join. Walks
     * {@link NotificationAuditService#findByRequestId(String)} →
     * {@link DeliveryEventStore#findByProviderMessageId(String, String)}.
     *
     * <p>Four outcomes:
     * <ul>
     *   <li>Audit not found → 404 ({@code NoOpAuditService} returns
     *       {@code Optional.empty()} for everything, so operators
     *       without a real audit backend see this on every requestId).</li>
     *   <li>Audit found, {@code providerMessageId} null →
     *       {@code auditState: "incomplete"}, empty entries — the
     *       send hasn't completed yet, retry the query in a moment.</li>
     *   <li>Audit found, {@code providerMessageId} set, no events →
     *       {@code auditState: "complete"}, empty entries — send
     *       completed but no callbacks have arrived (yet).</li>
     *   <li>Audit found, events present → {@code auditState: "complete"}
     *       with the events.</li>
     * </ul>
     */
    private ResponseEntity<Map<String, Object>> getDeliveryEventsByRequestId(
            String requestId, int limit, boolean includeRaw,
            Map<String, Object> result, DeliveryEventStore store) {

        java.util.Optional<NotificationAudit> auditOpt = auditService.findByRequestId(requestId);
        if (auditOpt.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put(FIELD_MESSAGE,
                    "No audit record for requestId=" + sanitizeForLog(requestId)
                            + ". Either the requestId is unknown, or the audit "
                            + "service is not configured (the default NoOpAuditService "
                            + "returns empty for every lookup).");
            return ResponseEntity.status(404).body(body);
        }

        NotificationAudit audit = auditOpt.get();
        result.put(FIELD_REQUEST_ID, requestId);
        result.put("provider", audit.getProvider());
        result.put(FIELD_PROVIDER_MESSAGE_ID, audit.getProviderMessageId());

        if (audit.getProviderMessageId() == null || audit.getProviderMessageId().isBlank()
                || audit.getProvider() == null || audit.getProvider().isBlank()) {
            // Send hasn't returned a provider message id yet — that's
            // the "still in flight" state.
            result.put("auditState", "incomplete");
            result.put(FIELD_ENTRIES, java.util.List.of());
            result.put(FIELD_MESSAGE,
                    "Audit record found but provider/providerMessageId is not yet set; "
                            + "the send may still be in flight. Retry shortly.");
            return ResponseEntity.ok(result);
        }

        result.put("auditState", "complete");
        java.util.Optional<java.util.List<DeliveryEvent>> source =
                store.findByProviderMessageId(audit.getProvider(), audit.getProviderMessageId());
        if (source.isPresent()) {
            int safeLimit = Math.clamp(limit, 1, 1_000);
            result.put(FIELD_ENTRIES, source.get().stream()
                    .limit(safeLimit)
                    .map(e -> toAdminEntry(e, includeRaw))
                    .toList());
        } else {
            result.put(FIELD_ENTRIES, java.util.List.of());
            result.put(FIELD_MESSAGE,
                    "Backing store does not support lookup; query the store directly.");
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Map a {@link DeliveryEvent} to the admin-endpoint shape.
     * Routing identifiers always included; the raw provider attribute
     * map is gated behind {@code includeRaw} to avoid leaking
     * recipient identifiers by default.
     */
    private Map<String, Object> toAdminEntry(DeliveryEvent event, boolean includeRaw) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", event.timestamp().toString());
        m.put("providerName", event.providerName());
        m.put(FIELD_PROVIDER_MESSAGE_ID, event.providerMessageId());
        m.put("providerEventId", event.providerEventId());
        m.put(FIELD_STATUS, event.status().name());
        m.put("reason", event.reason());
        if (includeRaw) {
            m.put("attributes", event.attributes());
        }
        return m;
    }

    /**
     * Look up a single audit record by request id (DD-20). Returns
     * the audit row as stored — {@link NotificationAudit} already
     * holds a PII-masked recipient summary via DD-07, so no further
     * redaction is applied.
     *
     * <p>{@code 404} on miss covers both "requestId truly unknown"
     * and "no real audit backend wired" — same overload DD-18 uses
     * on the joined endpoint.
     */
    @GetMapping("/audit/{requestId}")
    @Operation(summary = "Look up an audit record (DD-20)",
            description = "Returns the NotificationAudit row for a given "
                    + "requestId. Recipient is PII-masked at write time "
                    + "(DD-07). 404 when not found (or when the default "
                    + "NoOpAuditService is the configured backend).")
    public ResponseEntity<Map<String, Object>> getAudit(
            @PathVariable("requestId") String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of(FIELD_MESSAGE, "requestId is required"));
        }
        Optional<NotificationAudit> auditOpt = auditService.findByRequestId(requestId);
        if (auditOpt.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(Map.of(FIELD_MESSAGE,
                            "No audit record for requestId=" + sanitizeForLog(requestId)
                                    + ". (NoOpAuditService is the default; wire a real "
                                    + "audit backend to populate this endpoint.)"));
        }
        return ResponseEntity.ok(toAdminAuditEntry(auditOpt.get()));
    }

    /**
     * List the most-recent audit rows for a tenant (DD-20).
     *
     * <p>{@code tenantId} is required — cross-tenant recent is
     * rejected for the same blast-radius reasoning bulk DLQ replay
     * uses (DD-19).
     *
     * <p>When the backend returns {@code Optional.empty()} (the
     * default {@code NoOpAuditService} does this, as does any
     * backend that can't iterate cheaply), the response is {@code 200}
     * with {@code entries: null} and an explanatory message — same
     * shape {@code DeadLetterStore.snapshot()} and
     * {@code DeliveryEventStore.snapshot()} use.
     */
    @GetMapping("/audit/recent")
    @Operation(summary = "Recent audit rows (DD-20)",
            description = "Returns the most recent N audit rows for a "
                    + "given tenant, ordered most-recent-first. limit defaults "
                    + "to 50, capped at 200. Returns 200 with entries=null "
                    + "when the audit backend doesn't support listing.")
    public ResponseEntity<Map<String, Object>> getRecentAudit(
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {

        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of(FIELD_MESSAGE, "tenantId is required"));
        }
        int safeLimit = Math.clamp(limit, 1, 200);
        Optional<List<NotificationAudit>> recent = auditService.findRecent(tenantId, safeLimit);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_TENANT_ID, tenantId);
        body.put("limit", safeLimit);
        if (recent.isEmpty()) {
            body.put(FIELD_ENTRIES, null);
            body.put(FIELD_MESSAGE,
                    "Audit backend does not support recent listing; "
                            + "query the backend directly or look up by requestId.");
            return ResponseEntity.ok(body);
        }
        body.put(FIELD_ENTRIES, recent.get().stream()
                .map(this::toAdminAuditEntry)
                .toList());
        return ResponseEntity.ok(body);
    }

    /**
     * Map a {@link NotificationAudit} to the admin-endpoint shape.
     * Recipient is already masked at write time (DD-07) so the audit
     * record can be surfaced as-is.
     */
    private Map<String, Object> toAdminAuditEntry(NotificationAudit audit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(FIELD_REQUEST_ID, audit.getRequestId());
        m.put("correlationId", audit.getCorrelationId());
        m.put(FIELD_TENANT_ID, audit.getTenantId());
        m.put(FIELD_CALLER_ID, audit.getCallerId());
        m.put(FIELD_REPLAY_OF, audit.getReplayOf());
        m.put("notificationType", audit.getNotificationType());
        m.put(FIELD_CHANNEL, audit.getChannel() != null ? audit.getChannel().name() : null);
        m.put("provider", audit.getProvider());
        m.put("recipientSummary", audit.getRecipientSummary());
        m.put(FIELD_STATUS, audit.getStatus() != null ? audit.getStatus().name() : null);
        m.put(FIELD_PROVIDER_MESSAGE_ID, audit.getProviderMessageId());
        m.put(FIELD_ERROR_CODE, audit.getErrorCode());
        m.put(FIELD_ERROR_MESSAGE, audit.getErrorMessage());
        m.put("receivedAt", audit.getReceivedAt() != null ? audit.getReceivedAt().toString() : null);
        m.put("processedAt", audit.getProcessedAt() != null ? audit.getProcessedAt().toString() : null);
        m.put("sentAt", audit.getSentAt() != null ? audit.getSentAt().toString() : null);
        m.put("deliveredAt", audit.getDeliveredAt() != null ? audit.getDeliveredAt().toString() : null);
        m.put("templateId", audit.getTemplateId());
        return m;
    }

    /**
     * Clear template cache for a tenant.
     */
    @PostMapping("/cache/templates/clear")
    public ResponseEntity<Map<String, String>> clearTemplateCache(
            @RequestParam(required = false) String tenantId) {

        if (tenantId != null) {
            templateEngine.clearCache(tenantId);
            return ResponseEntity.ok(Map.of(FIELD_MESSAGE, "Template cache cleared for tenant: " + tenantId));
        } else {
            templateEngine.clearAllCache();
            return ResponseEntity.ok(Map.of(FIELD_MESSAGE, "All template caches cleared"));
        }
    }

    // ========== Helper Methods ==========

    private Map<String, Object> buildTenantsConfig() {
        Map<String, Object> tenants = new LinkedHashMap<>();
        properties.getTenants().forEach((tenantId, tenantConfig) ->
                tenants.put(tenantId, buildTenantConfig(tenantConfig)));
        return tenants;
    }

    private Map<String, Object> buildTenantConfig(TenantConfig tenantConfig) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> channels = new LinkedHashMap<>();

        // Include all channels (enabled and disabled)
        for (Channel channel : Channel.values()) {
            String channelName = channel.name().toLowerCase();
            ChannelConfig channelConfig = tenantConfig.getChannels().get(channelName);

            if (channelConfig != null) {
                channels.put(channelName, buildChannelConfig(channelConfig));
            } else {
                // Channel not configured
                channels.put(channelName, Map.of(FIELD_ENABLED, false, "providers", Map.of()));
            }
        }

        result.put("channels", channels);
        return result;
    }

    private Map<String, Object> buildChannelConfig(ChannelConfig channelConfig) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(FIELD_ENABLED, channelConfig.isEnabled());

        // Show default provider info
        String defaultProviderName = channelConfig.getDefaultProviderName();
        result.put("defaultProvider", defaultProviderName);
        result.put("providerRequiredInRequest", channelConfig.isProviderRequiredInRequest());

        if (channelConfig.getConfig() != null && !channelConfig.getConfig().isEmpty()) {
            result.put("config", maskSensitive(channelConfig.getConfig()));
        }

        Map<String, Object> providers = new LinkedHashMap<>();
        channelConfig.getProviders().forEach((providerName, providerConfig) -> {
            boolean isAutoDefault = channelConfig.getProviders().size() == 1;
            providers.put(providerName, buildProviderConfig(providerConfig, isAutoDefault));
        });
        result.put("providers", providers);

        return result;
    }

    private Map<String, Object> buildProviderConfig(ProviderConfig providerConfig, boolean isAutoDefault) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Determine provider type
        if (providerConfig.getBeanName() != null) {
            result.put("type", "SPRING_BEAN");
            result.put("beanName", providerConfig.getBeanName());
        } else if (providerConfig.getFqcn() != null) {
            result.put("type", "FQCN");
            result.put("fqcn", providerConfig.getFqcn());
        } else {
            result.put("type", "BUILT_IN");
        }

        result.put(FIELD_STATUS, "ACTIVE");

        // Show default status
        if (isAutoDefault) {
            result.put(FIELD_IS_DEFAULT, true);
            result.put("defaultReason", "AUTO (single provider)");
        } else if (providerConfig.isDefault()) {
            result.put(FIELD_IS_DEFAULT, true);
            result.put("defaultReason", "CONFIGURED (default: true)");
        } else {
            result.put(FIELD_IS_DEFAULT, false);
        }

        // Mask sensitive config values
        if (providerConfig.getProperties() != null && !providerConfig.getProperties().isEmpty()) {
            result.put("config", maskSensitive(providerConfig.getProperties()));
        }

        return result;
    }

    /**
     * Mask sensitive values in configuration.
     */
    private Map<String, Object> maskSensitive(Map<String, Object> config) {
        Map<String, Object> masked = new LinkedHashMap<>();
        config.forEach((key, value) -> {
            if (isSensitive(key)) {
                masked.put(key, "***MASKED***");
            } else if (value instanceof Map) {
                masked.put(key, maskSensitive((Map<String, Object>) value));
            } else {
                masked.put(key, value);
            }
        });
        return masked;
    }

    private boolean isSensitive(String key) {
        String lower = key.toLowerCase().replace("-", "").replace("_", "");
        return SENSITIVE_KEYS.stream().anyMatch(s -> lower.contains(s.replace("-", "")));
    }
}
