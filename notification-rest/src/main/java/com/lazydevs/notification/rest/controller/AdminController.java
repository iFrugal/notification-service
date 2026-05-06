package com.lazydevs.notification.rest.controller;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.caller.CallerRegistry;
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
     * JSON field name for the channel identifier across admin responses.
     * Pulled out as a constant so a typo in one place doesn't ship with
     * an inconsistent envelope (and to satisfy Sonar's "duplicated
     * literal" rule).
     */
    private static final String FIELD_CHANNEL = "channel";

    /** JSON field name for human-readable status / explanation strings. */
    private static final String FIELD_MESSAGE = "message";

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
    private final NotificationService notificationService;

    public AdminController(NotificationProperties properties,
                           ProviderRegistry providerRegistry,
                           NotificationTemplateEngine templateEngine,
                           CallerRegistry callerRegistry,
                           java.util.Optional<RateLimiter> rateLimiter,
                           java.util.Optional<DeadLetterStore> deadLetterStore,
                           NotificationService notificationService) {
        this.properties = properties;
        this.providerRegistry = providerRegistry;
        this.templateEngine = templateEngine;
        this.callerRegistry = callerRegistry;
        this.rateLimiter = rateLimiter;
        this.deadLetterStore = deadLetterStore;
        this.notificationService = notificationService;
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
        return ResponseEntity.ok(buildTenantConfig(tenantId, tenantConfig));
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

        return ResponseEntity.ok(buildChannelConfig(channel, channelConfig));
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

        result.put("status", allHealthy ? "UP" : "DEGRADED");
        result.put("providers", providerStatus);

        // Kafka status (if enabled)
        if (properties.getKafka().isEnabled()) {
            result.put("kafka", Map.of(
                    "enabled", true,
                    "topic", properties.getKafka().getTopic(),
                    "groupId", properties.getKafka().getGroupId()
            ));
        }

        // Audit status
        result.put("audit", Map.of("enabled", properties.getAudit().isEnabled()));

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
        result.put("enabled", callerRegistry.isEnabled());
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
        result.put("enabled", cfg.isEnabled());
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
            body.put("enabled", false);
            body.put(FIELD_MESSAGE, "Dead-letter store is disabled. "
                    + "Enable with notification.dead-letter.enabled=true.");
            return ResponseEntity.status(503).body(body);
        }
        DeadLetterStore store = deadLetterStore.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true);
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
            result.put("entries", snapshot.get().stream()
                    .limit(safeLimit)
                    .map(this::toAdminEntry)
                    .toList());
        } else {
            result.put("entries", null);
            result.put(FIELD_MESSAGE,
                    "Backing store does not support snapshot iteration; query the store directly.");
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
            body.put("enabled", false);
            body.put(FIELD_MESSAGE, "Dead-letter store is disabled. "
                    + "Enable with notification.dead-letter.enabled=true.");
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
            body.put("originalRequestId", requestId);
            body.put("replayOf", requestId);
            body.put(FIELD_MESSAGE, "Replay errored before reaching provider; entry kept.");
            return ResponseEntity.status(500).body(body);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originalRequestId", requestId);
        body.put("newRequestId", response.requestId());
        body.put("replayOf", requestId);
        body.put("tenantId", entry.request().getTenantId());
        body.put("callerId", entry.request().getCallerId());
        body.put(FIELD_CHANNEL, entry.request().getChannel() != null
                ? entry.request().getChannel().name() : null);
        body.put("status", response.status().name());

        if (response.status() == NotificationStatus.FAILED
                || response.status() == NotificationStatus.REJECTED) {
            // Replay reached the provider but the provider failed again —
            // entry stays. 502 makes "this didn't work" loud in operator
            // tooling rather than buried in a 200 body.
            body.put("errorCode", response.errorCode());
            body.put("errorMessage", response.errorMessage());
            body.put(FIELD_MESSAGE, "Replay failed; entry kept in DLQ.");
            return ResponseEntity.status(502).body(body);
        }

        // Successful replay → drop the original from the DLQ. remove()
        // is documented to never throw and to return false on a missing
        // entry, so the worst case is a stale entry remains visible
        // until size pressure or a subsequent replay attempt.
        boolean removed = store.remove(resolvedTenantId, requestId);
        body.put("removedFromDlq", removed);
        body.put(FIELD_MESSAGE, "Replay submitted; entry removed from DLQ on successful send.");
        return ResponseEntity.ok(body);
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
        m.put("tenantId", entry.request().getTenantId());
        m.put("callerId", entry.request().getCallerId());
        m.put(FIELD_CHANNEL, entry.request().getChannel() != null
                ? entry.request().getChannel().name() : null);
        m.put("requestId", entry.request().getRequestId());
        m.put("attempts", entry.attempts());
        m.put("failureType", entry.failureType().name());
        m.put("errorCode", entry.response().errorCode());
        m.put("errorMessage", entry.response().errorMessage());
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
        properties.getTenants().forEach((tenantId, tenantConfig) -> {
            tenants.put(tenantId, buildTenantConfig(tenantId, tenantConfig));
        });
        return tenants;
    }

    private Map<String, Object> buildTenantConfig(String tenantId, TenantConfig tenantConfig) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> channels = new LinkedHashMap<>();

        // Include all channels (enabled and disabled)
        for (Channel channel : Channel.values()) {
            String channelName = channel.name().toLowerCase();
            ChannelConfig channelConfig = tenantConfig.getChannels().get(channelName);

            if (channelConfig != null) {
                channels.put(channelName, buildChannelConfig(channelName, channelConfig));
            } else {
                // Channel not configured
                channels.put(channelName, Map.of("enabled", false, "providers", Map.of()));
            }
        }

        result.put("channels", channels);
        return result;
    }

    private Map<String, Object> buildChannelConfig(String channelName, ChannelConfig channelConfig) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", channelConfig.isEnabled());

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

        result.put("status", "ACTIVE");

        // Show default status
        if (isAutoDefault) {
            result.put("isDefault", true);
            result.put("defaultReason", "AUTO (single provider)");
        } else if (providerConfig.isDefault()) {
            result.put("isDefault", true);
            result.put("defaultReason", "CONFIGURED (default: true)");
        } else {
            result.put("isDefault", false);
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
