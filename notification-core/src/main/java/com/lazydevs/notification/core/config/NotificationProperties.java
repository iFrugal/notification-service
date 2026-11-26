package com.lazydevs.notification.core.config;

import com.lazydevs.notification.core.idempotency.IdempotencyFailurePolicy;
import com.lazydevs.notification.core.store.StoreType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for the notification service.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

    /**
     * Default tenant ID when X-Tenant-Id header is not provided
     */
    private String defaultTenant = "default";

    /**
     * Provider fallback to {@link #defaultTenant} for tenants that have no
     * entry under {@code notification.tenants} (DD-28).
     *
     * @since 1.2.1
     */
    private TenantFallbackProperties tenantFallback = new TenantFallbackProperties();

    /**
     * Provider lifecycle settings shared by every tenant (DD-29).
     *
     * @since 1.2.1
     */
    private ProvidersProperties providers = new ProvidersProperties();

    /**
     * REST API configuration
     */
    private RestProperties rest = new RestProperties();

    /**
     * Kafka configuration
     */
    private KafkaProperties kafka = new KafkaProperties();

    /**
     * Audit configuration
     */
    @Valid
    private AuditProperties audit = new AuditProperties();

    /**
     * Template configuration
     */
    private TemplateProperties template = new TemplateProperties();

    /**
     * Idempotency configuration (see DD-10).
     */
    @Valid
    private IdempotencyProperties idempotency = new IdempotencyProperties();

    /**
     * Caller-registry configuration (see DD-11). Off by default — turning
     * it on populates the admin endpoint and (optionally) enforces strict
     * admission of {@code X-Service-Id} values.
     */
    private CallerRegistryProperties callerRegistry = new CallerRegistryProperties();

    /**
     * Rate-limit configuration (see DD-12). Off by default — turning it
     * on activates the {@code Bucket4jRateLimiter} bean and engages the
     * pre-dispatch token-bucket check inside
     * {@code DefaultNotificationService.send()}.
     */
    @Valid
    private RateLimitProperties rateLimit = new RateLimitProperties();

    /**
     * Retry configuration (see DD-13). Off by default — turning it on
     * wraps the provider call in {@code RetryExecutor} with exponential
     * backoff + jitter.
     */
    @Valid
    private RetryProperties retry = new RetryProperties();

    /**
     * Dead-letter store configuration (see DD-13). Off by default. When
     * enabled, retry-exhausted and permanent failures are recorded to
     * the configured {@code DeadLetterStore} for operator inspection
     * via {@code GET /admin/dead-letter}.
     */
    @Valid
    private DeadLetterProperties deadLetter = new DeadLetterProperties();

    /**
     * Redis-backed distributed-state configuration (see DD-14). Lives
     * in the parent properties so it's bound by the same
     * {@code ConfigurationProperties} processor whether or not
     * {@code notification-redis} is on the classpath. The Redis bean
     * implementations themselves are gated on {@code @ConditionalOnClass}
     * so unused configuration on a no-Redis deployment is harmless.
     */
    @Valid
    private RedisProperties redis = new RedisProperties();

    /**
     * Store family selection for the stateful features (idempotency,
     * rate limiting, dead letters, delivery events).
     */
    @Valid
    private StoreProperties store = new StoreProperties();

    /**
     * Webhook ingestion configuration (see DD-16). Off by default —
     * turning it on activates {@code POST /webhooks/{provider}/...}
     * handlers for provider-side delivery callbacks.
     */
    @Valid
    private WebhookProperties webhooks = new WebhookProperties();

    /**
     * Persistent {@code DeliveryEventStore} configuration (see DD-17).
     * Off by default. When enabled, ingested delivery callbacks are
     * retained in a bounded buffer queryable via
     * {@code GET /admin/delivery-events}.
     */
    @Valid
    private DeliveryEventProperties deliveryEvents = new DeliveryEventProperties();

    /**
     * Actuator health indicator config (see DD-21). Each indicator
     * registers automatically when its backing SPI bean is present;
     * the only knob is the DLQ near-full threshold.
     */
    @Valid
    private HealthProperties health = new HealthProperties();

    /**
     * Recipient guard (DD-34). Off by default.
     */
    private RecipientGuardProperties recipientGuard = new RecipientGuardProperties();

    /**
     * Tenant-specific configurations
     */
    private Map<String, TenantConfig> tenants = new LinkedHashMap<>();

    @Data
    public static class RestProperties {
        /**
         * REST API (controllers, filters, OpenAPI metadata, webhooks) is opt-in.
         * Set to {@code true} to expose it; the standalone server does so in
         * its {@code application.yml}.
         */
        private boolean enabled = false;
        /**
         * Path prefix for every notification endpoint. The tenant and
         * caller-admission filters are restricted to {@code <base-path>/*}.
         */
        private String basePath = "/api/v1";
    }

    @Data
    public static class KafkaProperties {
        private boolean enabled = false;
        private String topic = "notifications";
        private String groupId = "notification-service";
    }

    @Data
    public static class AuditProperties {
        /**
         * Keep an audit record per send, the data behind
         * {@code NotificationService.find} (DD-33). The record lives in the
         * store family of {@code notification.store.type} (memory or jdbc).
         */
        private boolean enabled = false;
        /** Not implemented; the request payload is never stored. */
        private boolean storeRequestPayload = false;
        /** Not implemented; the provider response is never stored. */
        private boolean storeResponsePayload = false;
        /** Days an audit record is kept before it expires and becomes purgeable. */
        @Min(value = 1, message = "audit retention-days must be at least 1")
        private int retentionDays = 90;
        /** Not implemented; audit writes are synchronous and never fail a send. */
        private boolean async = true;
        /**
         * Bound of the in-memory audit store ({@code notification.store.type=memory});
         * least recently written records are evicted first.
         *
         * @since 1.2.1
         */
        @Min(value = 1, message = "audit max-entries must be at least 1")
        private long maxEntries = 10_000L;
    }

    @Data
    public static class TemplateProperties {
        /**
         * Root of the template tree; templates resolve at
         * {@code <base-path><tenant>/<channel>/<id>.ftl}, then under {@code default/}.
         */
        private String basePath = "classpath:/templates/";
        private boolean cacheEnabled = true;
        /**
         * Seconds a loaded template stays cached before it is read again. 0 or negative
         * keeps it until it is evicted or the cache is cleared.
         */
        private int cacheTtlSeconds = 3600;
        /**
         * Maximum number of templates cached across all tenants. 0 or negative is unbounded.
         */
        private long cacheMaxSize = 1000;
        /**
         * Render HTML email bodies in FreeMarker's HTML output format, so interpolated values
         * are HTML-escaped. {@code escapeHtml()} results are not escaped twice and
         * {@code ?no_esc} prints trusted markup as is.
         */
        private boolean autoEscape = false;
        /**
         * Put the persistence-utils helpers {@code eval}, {@code file}, {@code js},
         * {@code serde_deserializeToMap} and {@code serde_serialize} into every template model
         * and leave FreeMarker's {@code ?new} unrestricted, as up to 1.2.
         * {@code file} reads any path the process can read and {@code js} runs script functions,
         * so {@code true} is deprecated: it logs a WARN at startup and the default becomes
         * {@code false} in 2.0. {@code uuid}, {@code trim} and the notification helpers are
         * available either way (DD-26).
         *
         * @since 1.2.1
         */
        private boolean legacyHelpers = true;
        /**
         * Render the template before the idempotency check, rate limit, audit and dispatch, and
         * throw {@code TemplateRenderException} to the caller (HTTP 422 over REST) when it is
         * missing, invalid, does not fit the data or renders an empty email; nothing is stored
         * for such a request. {@code false} keeps the 1.2 flow, where a render failure is a
         * stored {@code FAILED} attempt. Planned to default to {@code true} in 2.0 (DD-27).
         *
         * @since 1.2.1
         */
        private boolean validateBeforeDispatch = false;
    }

    /**
     * Idempotency-key handling. See {@code docs/design-decisions/10-idempotency.md}.
     */
    @Data
    @EqualsAndHashCode(doNotUseGetters = true) // the generated methods must not call the deprecated getters
    @ToString(doNotUseGetters = true)
    public static class IdempotencyProperties {
        /** Master switch — false disables dedup entirely (the request field is silently ignored). */
        private boolean enabled = true;

        /** TTL after which a key is forgotten and treated as fresh. ISO-8601 duration. */
        private java.time.Duration ttl = java.time.Duration.ofHours(24);

        /**
         * Caffeine bound. Once exceeded, least-recently-used entries are
         * evicted before TTL — operators sizing for high-throughput
         * deployments should raise this. Ignored by Redis-backed stores.
         */
        private long maxEntries = 100_000L;

        /**
         * Deprecated alias of {@link #failurePolicy}: {@code true} is
         * {@code RELEASE_ALL}, {@code false} is {@code KEEP}. Unset
         * ({@code null}) behaves as {@code true}, the default since 1.1.2.
         * When both are set and disagree, {@code failure-policy} wins and a
         * WARN is logged at startup. Has no effect with a custom store that
         * does not implement {@code IdempotencyStore.release}.
         *
         * @since 1.1.2
         * @deprecated since 1.2.1, use {@code notification.idempotency.failure-policy}
         */
        @Deprecated(since = "1.2.1")
        @SuppressWarnings("java:S1133") // kept for source compatibility until the next major version
        private Boolean retryAfterFailure;

        /**
         * What happens to a key after a send under it failed (DD-32):
         * {@code RELEASE_ALL} (retry dispatches again), {@code KEEP} (retry
         * gets 409 until the TTL) or {@code RELEASE_TRANSIENT} (release only
         * after a TRANSIENT failure; other failures are replayed). Unset
         * follows {@link #retryAfterFailure}, which gives {@code RELEASE_ALL}.
         *
         * @since 1.2.1
         */
        private IdempotencyFailurePolicy failurePolicy;

        /**
         * Refuse a request whose key was already used for a different request
         * (different fingerprint, DD-32) with HTTP 409
         * ({@code IdempotencyKeyReusedException}). Off by default: the first
         * response is replayed, as in 1.2.0. A record without a fingerprint
         * (written by 1.2.0 or earlier) is never compared.
         *
         * @since 1.2.1
         */
        private boolean rejectKeyReuse = false;

        /**
         * How long a send may hold a key {@code IN_PROGRESS} before a request
         * under the same key settles it (DD-32): the record becomes a
         * {@code FAILED} outcome with error code {@code IN_PROGRESS_TIMEOUT}
         * and failure type {@code AMBIGUOUS}, which is replayed and never
         * released, because the first attempt may have been delivered. Unset
         * (the default) answers with HTTP 409 until the TTL, as in 1.2.0.
         * Must be positive when set.
         *
         * @since 1.2.1
         */
        private java.time.Duration inProgressTimeout;

        /**
         * Keep the error message of a stored {@code FAILED} or {@code REJECTED}
         * response, scrubbed by the {@code MessageScrubber} (email addresses
         * and phone numbers become placeholders), so a replayed failure says
         * what went wrong (DD-32). Off by default: the stored copy has no
         * error message, as in 1.1.2 to 1.2.0.
         *
         * @since 1.2.1
         */
        private boolean storeScrubbedErrorMessage = false;

        /**
         * Whether a failed key is released for a retry, the 1.1.2 view of
         * {@link #resolvedFailurePolicy()}.
         *
         * @return {@code false} only when {@code retry-after-failure} is
         *         explicitly {@code false}
         * @deprecated since 1.2.1, use {@link #resolvedFailurePolicy()}
         */
        @Deprecated(since = "1.2.1")
        @SuppressWarnings("java:S1133") // kept for source compatibility until the next major version
        public boolean isRetryAfterFailure() {
            return !Boolean.FALSE.equals(retryAfterFailure);
        }

        /**
         * The effective failure policy: {@link #failurePolicy} when set,
         * otherwise {@code KEEP} for {@code retry-after-failure=false} and
         * {@code RELEASE_ALL} for anything else.
         *
         * @return the policy, never {@code null}
         * @since 1.2.1
         */
        public IdempotencyFailurePolicy resolvedFailurePolicy() {
            if (failurePolicy != null) {
                return failurePolicy;
            }
            return Boolean.FALSE.equals(retryAfterFailure)
                    ? IdempotencyFailurePolicy.KEEP
                    : IdempotencyFailurePolicy.RELEASE_ALL;
        }

        /**
         * @return {@code true} when {@code failure-policy} and the deprecated
         *         {@code retry-after-failure} are both set and disagree
         * @since 1.2.1
         */
        public boolean hasConflictingFailurePolicy() {
            if (failurePolicy == null || retryAfterFailure == null) {
                return false;
            }
            IdempotencyFailurePolicy alias = Boolean.TRUE.equals(retryAfterFailure)
                    ? IdempotencyFailurePolicy.RELEASE_ALL
                    : IdempotencyFailurePolicy.KEEP;
            return alias != failurePolicy;
        }

        @AssertTrue(message = "idempotency in-progress-timeout must be positive")
        public boolean isInProgressTimeoutValid() {
            return inProgressTimeout == null || inProgressTimeout.isPositive();
        }

        /**
         * Backing store. Deprecated: select the store family with
         * {@code notification.store.type} (or
         * {@code notification.redis.idempotency.enabled}) instead; this value
         * is only logged by the Caffeine store.
         */
        @Deprecated(since = "1.1.0")
        private String store = "caffeine";
    }

    /**
     * Store family selection. See {@link com.lazydevs.notification.core.store.StoreFeature}
     * for how the family of each feature resolves.
     */
    @Data
    public static class StoreProperties {
        /**
         * Store family backing every enabled stateful feature: {@code memory}
         * (in-process, notification-core), {@code redis} (notification-redis)
         * or {@code jdbc} (notification-store-jdbc). A per-feature
         * {@code notification.redis.<feature>.enabled} toggle, when set,
         * overrides this for that feature.
         */
        private StoreType type = StoreType.MEMORY;
    }

    /**
     * Caller-registry handling. See {@code docs/design-decisions/11-caller-identity.md}.
     *
     * <p>Default state ({@code enabled=false}) is the no-op: the
     * {@code X-Service-Id} header is still read into {@code callerId}, but
     * unknown values are accepted silently and the admin endpoint reports
     * the registry as off.
     */
    @Data
    public static class CallerRegistryProperties {
        /** Master switch for caller-registry behaviour. */
        private boolean enabled = false;

        /**
         * If {@code true} <strong>and</strong> {@code enabled=true}, an
         * incoming request whose resolved {@code callerId} is non-null and
         * NOT in {@link #knownServices} is rejected with HTTP 403. A null
         * {@code callerId} (no header sent) is always accepted — strict
         * mode is about admitting only listed callers, not about forcing
         * every caller to identify itself.
         */
        private boolean strict = false;

        /**
         * Advisory list of known caller-ids. When {@link #enabled} is
         * {@code false} this is purely documentation. When {@code true}
         * unknown callers are logged at WARN level (and rejected if
         * {@link #strict}).
         */
        private List<String> knownServices = new ArrayList<>();
    }

    /**
     * Recipient guard (DD-34): an allow-list per channel, with redirect or
     * drop for everything else. Off by default.
     *
     * <pre>
     * notification:
     *   recipient-guard:
     *     enabled: true
     *     channels:
     *       email:
     *         allowed: ["@example.com", "qa.lead@example.org"]
     *         redirect-to: qa-inbox@example.com
     *       sms:
     *         allowed: ["+15550100"]
     * </pre>
     *
     * @since 1.2.1
     */
    @Data
    public static class RecipientGuardProperties {
        /** Master switch for the configuration-driven guard. */
        private boolean enabled = false;

        /**
         * Rules per channel name ({@code email}, {@code sms},
         * {@code whatsapp}; case-insensitive). A channel without rules is not
         * guarded. Push recipients are never guarded.
         */
        private Map<String, RecipientGuardChannel> channels = new LinkedHashMap<>();
    }

    /**
     * Recipient guard rules for one channel (DD-34).
     *
     * @since 1.2.1
     */
    @Data
    public static class RecipientGuardChannel {
        /**
         * Allowed recipients. {@code @example.com} allows every email address
         * in exactly that domain (case-insensitive, subdomains excluded);
         * anything else is one address (case-insensitive) or phone number
         * (compared on its digits only).
         */
        private List<String> allowed = new ArrayList<>();

        /**
         * Address or number that takes the place of each recipient that is
         * not allowed. Must itself be allowed. Unset drops those recipients;
         * when none is left the send fails with {@code RECIPIENT_BLOCKED}.
         */
        private String redirectTo;

        /**
         * Email only: put in front of the subject when something was
         * redirected; {@code {originals}} becomes the comma-separated original
         * addresses. An empty value disables the prefix.
         */
        private String subjectPrefix = "[redirected: {originals}] ";
    }

    /**
     * Rate-limit handling. See {@code docs/design-decisions/12-rate-limiting.md}.
     *
     * <p>Off by default. When enabled, every request through
     * {@code DefaultNotificationService.send()} consumes a token from the
     * bucket matching the most-specific rule for its
     * {@code (tenantId, callerId, channel)} triple. Buckets are
     * Bucket4j-backed, in-process by default; a future Redis-backed bean
     * will substitute via {@code @ConditionalOnMissingBean}.
     */
    @Data
    public static class RateLimitProperties {
        /** Master switch — false leaves the {@code RateLimiter} bean absent. */
        private boolean enabled = false;

        /**
         * Default rule applied when no override matches the request's
         * {@code (tenantId, callerId, channel)} triple. Generous defaults
         * — operators almost always want to override per-tenant or
         * per-caller; the default is just a backstop that prevents
         * unbounded fan-out.
         */
        @Valid
        @NotNull
        private RateLimitRule defaultRule = new RateLimitRule(200, 100, java.time.Duration.ofSeconds(1));

        /**
         * Targeted rule overrides. Match precedence is most-specific-wins:
         * {@code (tenant, caller, channel)} > {@code (tenant, caller)}
         * > {@code (tenant)} > {@code byChannel default} > {@code defaultRule}.
         * Within the same specificity, configuration order is the tiebreaker.
         */
        @Valid
        private List<RateLimitOverride> overrides = new ArrayList<>();

        /**
         * Per-channel rule defaults (DD-23). Sits between per-tuple
         * {@link #overrides} and the global {@link #defaultRule} in the
         * precedence chain. Lets operators set "globally, SMS is more
         * bounded than the catch-all" without enumerating an override
         * per tenant.
         *
         * <p>Keys are channel names, case-insensitive (folded to
         * lowercase at lookup). Values are full {@link RateLimitRule}s.
         *
         * <p>A tenant who's negotiated a higher SMS bucket via the
         * {@link #overrides} list still wins — that's the deliberate
         * shape of "channel-default as backstop, tenant-override as
         * agreement" (DD-23 §"Why byChannel between specific overrides
         * and global default").
         */
        @Valid
        private Map<String, RateLimitRule> byChannel = new LinkedHashMap<>();

        /**
         * Whether a send that was admitted by the limiter but never reached
         * a provider gives its token back (DD-30), through
         * {@link com.lazydevs.notification.api.ratelimit.RateLimiter#refund}.
         * {@code false} (the default) keeps the 1.2.0 behaviour: every
         * admitted request costs one token.
         *
         * @since 1.2.1
         */
        private boolean refundUnsent = false;
    }

    /**
     * Which requests share one token bucket (DD-30).
     *
     * @since 1.2.1
     */
    public enum RateLimitScope {
        /**
         * One bucket per {@code (tenant, caller, channel)}; the 1.2.0 key.
         */
        TENANT_CALLER,

        /**
         * One bucket per channel, shared by every tenant and caller whose
         * request resolves to the rule, for example a provider account quota.
         */
        CHANNEL
    }

    /**
     * A rate-limit rule: bucket capacity, refill amount, and refill period.
     * Bucket4j semantics: at any time the bucket holds at most
     * {@link #capacity} tokens; every {@link #refillPeriod}, up to
     * {@link #refillTokens} are added (greedy refill — they're not held
     * back for a fixed-window boundary).
     *
     * <p>Since 1.2.1 a rule can instead list several {@link #bandwidths}, all
     * of which must allow a send (for example 2 per minute and 5 per hour),
     * and can choose its bucket {@link #scope}. Setting {@link #bandwidths}
     * together with any of {@code capacity}, {@code refill-tokens} or
     * {@code refill-period} is a configuration error.
     */
    @Data
    @lombok.NoArgsConstructor
    public static class RateLimitRule {
        /** Maximum tokens in the bucket — the burst tolerance. */
        @Min(value = 1, message = "rate-limit capacity must be at least 1")
        private long capacity = 200;

        /** Tokens added each refill period (must be {@code <= capacity}). */
        @Min(value = 1, message = "rate-limit refillTokens must be at least 1")
        private long refillTokens = 100;

        /** How often the refill happens. ISO-8601 duration. Non-zero, positive. */
        @NotNull
        private java.time.Duration refillPeriod = java.time.Duration.ofSeconds(1);

        /**
         * Several limits enforced together; a send is allowed only when every
         * band has a token. Empty (the default) means the single band given
         * by {@link #capacity}, {@link #refillTokens} and {@link #refillPeriod}.
         *
         * @since 1.2.1
         */
        @Valid
        private List<RateLimitBandwidth> bandwidths = new ArrayList<>();

        /**
         * Which requests share the bucket this rule builds.
         * {@link RateLimitScope#TENANT_CALLER} (the default) is the 1.2.0 key.
         *
         * @since 1.2.1
         */
        @NotNull(message = "rate-limit scope must not be null")
        private RateLimitScope scope = RateLimitScope.TENANT_CALLER;

        /**
         * Whether a single-band field was set through its setter, which is
         * what configuration binding calls. Field defaults and the
         * three-argument constructor do not count, so a rule built from
         * {@code new RateLimitRule(...)} can still be given bandwidths by
         * configuration.
         */
        @lombok.Getter(lombok.AccessLevel.NONE)
        @lombok.Setter(lombok.AccessLevel.NONE)
        @lombok.EqualsAndHashCode.Exclude
        @lombok.ToString.Exclude
        private boolean singleBandSet;

        /**
         * A single-band rule with the default {@link RateLimitScope#TENANT_CALLER} scope.
         */
        public RateLimitRule(long capacity, long refillTokens, java.time.Duration refillPeriod) {
            this.capacity = capacity;
            this.refillTokens = refillTokens;
            this.refillPeriod = refillPeriod;
        }

        public void setCapacity(long capacity) {
            this.capacity = capacity;
            this.singleBandSet = true;
        }

        public void setRefillTokens(long refillTokens) {
            this.refillTokens = refillTokens;
            this.singleBandSet = true;
        }

        public void setRefillPeriod(java.time.Duration refillPeriod) {
            this.refillPeriod = refillPeriod;
            this.singleBandSet = true;
        }

        /**
         * The bands this rule enforces: {@link #bandwidths} when set,
         * otherwise the single band from {@link #capacity},
         * {@link #refillTokens} and {@link #refillPeriod}.
         *
         * @since 1.2.1
         */
        public List<RateLimitBandwidth> effectiveBandwidths() {
            if (bandwidths != null && !bandwidths.isEmpty()) {
                return List.copyOf(bandwidths);
            }
            return List.of(new RateLimitBandwidth(capacity, refillTokens, refillPeriod));
        }

        /**
         * Bean-validation predicate rejecting a rule that sets both
         * {@link #bandwidths} and a single-band field, because it is not
         * clear which one the operator meant.
         */
        @AssertTrue(message = "rate-limit rule sets both 'bandwidths' and 'capacity'/'refill-tokens'/"
                + "'refill-period'; use one or the other")
        public boolean isBandwidthsExclusive() {
            return bandwidths == null || bandwidths.isEmpty() || !singleBandSet;
        }

        /**
         * Bean-validation predicate enforcing
         * {@code refillTokens <= capacity}. Refill bigger than capacity
         * means the bucket overflows on every refill, which is almost
         * certainly a config typo. Custom {@code @AssertTrue} rather
         * than a separate {@code @Constraint} so all the rule's
         * invariants stay in one file.
         */
        @AssertTrue(message = "rate-limit refillTokens must not exceed capacity")
        public boolean isRefillTokensWithinCapacity() {
            return refillTokens <= capacity;
        }

        /**
         * {@link #refillPeriod} must be strictly positive (not zero, not
         * negative). {@code @NotNull} above only catches missing values.
         */
        @AssertTrue(message = "rate-limit refillPeriod must be positive")
        public boolean isRefillPeriodPositive() {
            return refillPeriod != null && refillPeriod.isPositive();
        }
    }

    /**
     * One band of a multi-band {@link RateLimitRule} (DD-30): at most
     * {@link #capacity} tokens, refilled greedily by {@link #refillTokens}
     * every {@link #refillPeriod}. Every field is required.
     *
     * @since 1.2.1
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RateLimitBandwidth {
        /** Maximum tokens in this band. */
        @Min(value = 1, message = "rate-limit bandwidth capacity must be at least 1")
        private long capacity;

        /** Tokens added each refill period (must be {@code <= capacity}). */
        @Min(value = 1, message = "rate-limit bandwidth refillTokens must be at least 1")
        private long refillTokens;

        /** How often the refill happens. ISO-8601 duration, positive. */
        @NotNull(message = "rate-limit bandwidth refillPeriod is required")
        private java.time.Duration refillPeriod;

        /** Same invariant as {@link RateLimitRule#isRefillTokensWithinCapacity()}. */
        @AssertTrue(message = "rate-limit bandwidth refillTokens must not exceed capacity")
        public boolean isRefillTokensWithinCapacity() {
            return refillTokens <= capacity;
        }

        /** A missing period is reported by {@code @NotNull}, not here. */
        @AssertTrue(message = "rate-limit bandwidth refillPeriod must be positive")
        public boolean isRefillPeriodPositive() {
            return refillPeriod == null || refillPeriod.isPositive();
        }
    }

    /**
     * A targeted override on top of the default rate-limit rule. At least
     * {@link #tenant} must be set; {@link #caller} and {@link #channel}
     * narrow the match further.
     *
     * <p>Uses {@code @Getter}/{@code @Setter} (not {@code @Data}) on
     * purpose: {@code @Data} would auto-generate {@code equals}/
     * {@code hashCode}/{@code canEqual} that don't include the parent
     * {@link RateLimitRule}'s fields, producing subtly wrong equality.
     * The override is consumed only by Spring Boot configuration
     * binding and the in-memory limiter — neither needs equality
     * semantics — so omitting them is safer than half-implementing them.
     */
    @lombok.Getter
    @lombok.Setter
    public static class RateLimitOverride extends RateLimitRule {
        /** Tenant the rule applies to. Required. */
        @NotBlank(message = "rate-limit override tenant is required")
        private String tenant;

        /**
         * Caller-id the rule narrows to. Optional — leave {@code null} to
         * apply tenant-wide regardless of caller.
         */
        private String caller;

        /**
         * Channel the rule narrows to (e.g. {@code "email"}, {@code "sms"}).
         * Optional — leave {@code null} to apply across all channels.
         * Matched case-insensitively.
         */
        private String channel;
    }

    /**
     * Retry handling. See {@code docs/design-decisions/13-retries-and-dlq.md}.
     */
    @Data
    public static class RetryProperties {
        /** Master switch — false leaves the {@code RetryExecutor} disabled (single attempt). */
        private boolean enabled = false;

        /**
         * Total attempts including the first. {@code max-attempts: 1}
         * means "no retries". Capped at 10 — anything higher should
         * almost certainly be moved to async / DLQ instead. The cap is
         * enforced at binding so a misconfigured deployment can't
         * silently hang request threads on a 100-attempt backoff
         * window.
         */
        @Min(value = 1, message = "retry max-attempts must be at least 1")
        @Max(value = 10, message = "retry max-attempts must be at most 10 — use async + DLQ for higher")
        private int maxAttempts = 3;

        /** First backoff window. {@code Duration.ZERO} would defeat backoff. */
        @NotNull
        private java.time.Duration initialDelay = java.time.Duration.ofSeconds(1);

        /**
         * Exponential growth factor. {@code 2.0} doubles the delay each
         * attempt. {@code 1.0} disables exponential growth (constant delay).
         */
        @jakarta.validation.constraints.DecimalMin(value = "1.0",
                message = "retry multiplier must be >= 1.0")
        private double multiplier = 2.0;

        /** Cap on any single delay. Prevents runaway growth on long retry windows. */
        @NotNull
        private java.time.Duration maxDelay = java.time.Duration.ofSeconds(30);

        /**
         * Jitter fraction in {@code [0.0, 1.0]}. {@code 0.5} means the
         * actual delay is sampled uniformly from {@code ±50%} of the
         * computed value. Critical to avoid thundering-herd retries
         * when many sends fail simultaneously.
         */
        @jakarta.validation.constraints.DecimalMin(value = "0.0",
                message = "retry jitter must be in [0.0, 1.0]")
        @jakarta.validation.constraints.DecimalMax(value = "1.0",
                message = "retry jitter must be in [0.0, 1.0]")
        private double jitter = 0.5;

        /**
         * Longest provider retry hint (an HTTP {@code Retry-After}) the
         * executor waits for. A hint beyond it stops the retries: the
         * failure is returned at once and goes to the dead-letter store when
         * one is configured. Defaults to {@code max-delay}.
         */
        private java.time.Duration maxRetryAfter;

        @AssertTrue(message = "retry max-retry-after must be positive")
        public boolean isMaxRetryAfterValid() {
            return maxRetryAfter == null || maxRetryAfter.isPositive();
        }

        @AssertTrue(message = "retry initialDelay must be positive (zero defeats backoff)")
        public boolean isInitialDelayValid() {
            // Class doc says "Duration.ZERO would defeat backoff" — a
            // zero initial delay turns retries into a tight loop, which
            // is almost always a config typo. Enforce strictly positive.
            return initialDelay != null
                    && !initialDelay.isNegative()
                    && !initialDelay.isZero();
        }

        @AssertTrue(message = "retry maxDelay must be positive and >= initialDelay")
        public boolean isMaxDelayValid() {
            return maxDelay != null
                    && !maxDelay.isNegative()
                    && !maxDelay.isZero()
                    && (initialDelay == null || maxDelay.compareTo(initialDelay) >= 0);
        }

        /**
         * Per-channel rule overrides (DD-23). Keys are channel names
         * (case-insensitive — folded to lowercase at lookup). When a
         * request's channel matches a key, the corresponding
         * {@link RetryRule} replaces the global defaults for that
         * request. No match → global defaults stand.
         *
         * <p>YAML example:
         * <pre>
         * notification:
         *   retry:
         *     max-attempts: 3             # global
         *     by-channel:
         *       sms:                       # SMS is expensive — tight bound
         *         max-attempts: 2
         *         initial-delay: 2s
         *       email:                     # email is cheap — generous bound
         *         max-attempts: 5
         * </pre>
         */
        @Valid
        private Map<String, RetryRule> byChannel = new LinkedHashMap<>();

        /**
         * Resolve the effective retry rule for the given channel.
         * Returns the per-channel override if present, otherwise a
         * snapshot of the global defaults. Case-insensitive lookup.
         *
         * @param channel channel name (e.g. {@code "EMAIL"}, {@code "sms"});
         *                {@code null} returns the global defaults
         */
        public RetryRule ruleFor(String channel) {
            if (channel != null) {
                RetryRule override = byChannel.get(channel.toLowerCase(java.util.Locale.ROOT));
                if (override != null) {
                    return override;
                }
            }
            return globalRule();
        }

        /**
         * Snapshot the global fields as a {@link RetryRule} value. Used
         * as the fallback when no per-channel override matches and as
         * the shape exposed to the per-channel map.
         */
        private RetryRule globalRule() {
            RetryRule r = new RetryRule();
            r.setMaxAttempts(maxAttempts);
            r.setInitialDelay(initialDelay);
            r.setMultiplier(multiplier);
            r.setMaxDelay(maxDelay);
            r.setJitter(jitter);
            r.setMaxRetryAfter(maxRetryAfter);
            return r;
        }
    }

    /**
     * A single retry rule — same shape as the top-level
     * {@link RetryProperties} fields. Used as the value type of
     * {@code RetryProperties.byChannel} (DD-23).
     *
     * <p>Validation is duplicated from {@link RetryProperties} so a
     * misconfigured per-channel override fails binding the same way a
     * misconfigured global default does.
     */
    @Data
    public static class RetryRule {
        @Min(value = 1, message = "retry max-attempts must be at least 1")
        @Max(value = 10, message = "retry max-attempts must be at most 10 — use async + DLQ for higher")
        private int maxAttempts = 3;

        @NotNull
        private java.time.Duration initialDelay = java.time.Duration.ofSeconds(1);

        @jakarta.validation.constraints.DecimalMin(value = "1.0",
                message = "retry multiplier must be >= 1.0")
        private double multiplier = 2.0;

        @NotNull
        private java.time.Duration maxDelay = java.time.Duration.ofSeconds(30);

        @jakarta.validation.constraints.DecimalMin(value = "0.0",
                message = "retry jitter must be in [0.0, 1.0]")
        @jakarta.validation.constraints.DecimalMax(value = "1.0",
                message = "retry jitter must be in [0.0, 1.0]")
        private double jitter = 0.5;

        /**
         * Longest provider retry hint (an HTTP {@code Retry-After}) the
         * executor waits for under this rule. A hint beyond it stops the
         * retries and surfaces the failure. Defaults to this rule's
         * {@code max-delay}.
         */
        private java.time.Duration maxRetryAfter;

        /**
         * The effective {@code max-retry-after}: the configured value, or
         * {@code max-delay} when none is set.
         */
        public java.time.Duration effectiveMaxRetryAfter() {
            return maxRetryAfter != null ? maxRetryAfter : maxDelay;
        }

        @AssertTrue(message = "retry max-retry-after must be positive")
        public boolean isMaxRetryAfterValid() {
            return maxRetryAfter == null || maxRetryAfter.isPositive();
        }

        @AssertTrue(message = "retry initialDelay must be positive (zero defeats backoff)")
        public boolean isInitialDelayValid() {
            return initialDelay != null
                    && !initialDelay.isNegative()
                    && !initialDelay.isZero();
        }

        @AssertTrue(message = "retry maxDelay must be positive and >= initialDelay")
        public boolean isMaxDelayValid() {
            return maxDelay != null
                    && !maxDelay.isNegative()
                    && !maxDelay.isZero()
                    && (initialDelay == null || maxDelay.compareTo(initialDelay) >= 0);
        }
    }

    /**
     * Dead-letter store configuration. See
     * {@code docs/design-decisions/13-retries-and-dlq.md}.
     */
    @Data
    public static class DeadLetterProperties {
        /** Master switch — false leaves the {@code DeadLetterStore} bean absent. */
        private boolean enabled = false;

        /**
         * Maximum entries the in-memory store retains (Caffeine LRU).
         * Older entries fall off when this is exceeded. The DLQ is for
         * operator inspection, not for production state-of-the-world,
         * so the bound is intentionally modest.
         */
        @Min(value = 1, message = "dead-letter max-entries must be at least 1")
        private int maxEntries = 1_000;

        /**
         * How long a replay holds its claim on a dead-letter entry. While
         * the lease is live no other replay, on this or another replica,
         * picks the entry up; a successful replay removes it, a failed one
         * releases it, and a replica that dies mid-replay lets it lapse.
         * Make it longer than the slowest provider send, including retries.
         */
        private java.time.Duration replayLease = java.time.Duration.ofMinutes(5);

        /** A zero or negative lease would let two replays take the same entry. */
        @AssertTrue(message = "dead-letter replay-lease must be positive")
        public boolean isReplayLeasePositive() {
            return replayLease != null && replayLease.isPositive();
        }
    }

    /**
     * Actuator health-indicator config (DD-21).
     */
    @Data
    public static class HealthProperties {
        /**
         * Percentage of {@code maxEntries} at which the DLQ health
         * indicator flips from {@code UP} to {@code OUT_OF_SERVICE}.
         * Default 80%. The indicator is still answering (DLQ isn't
         * "broken") — but operators want their monitoring to alert
         * here, and {@code OUT_OF_SERVICE} is the actuator-side
         * signal for "drainable, not broken."
         *
         * <p>Set to {@code 0} to never flip (useful in dev).
         */
        @Min(value = 0, message = "dlq-near-full-percent must be 0-100")
        private int dlqNearFullPercent = 80;
    }

    /**
     * Persistent {@code DeliveryEventStore} config (DD-17). Off by
     * default. When enabled, the in-memory {@code InMemoryDeliveryEventStore}
     * bean registers and the {@code GET /admin/delivery-events}
     * endpoint becomes useful.
     *
     * <p>Default cap is larger than the DLQ's because delivery events
     * arrive at a rate proportional to send volume — a 1000-event
     * buffer would wrap on any meaningful traffic.
     */
    @Data
    public static class DeliveryEventProperties {
        /** Master switch — false leaves the {@code DeliveryEventStore} bean absent. */
        private boolean enabled = false;

        /**
         * Maximum events the in-memory store retains. Older entries
         * fall off when this is exceeded. Tune up for high-volume
         * deployments, but remember every event sits in process
         * memory — the Redis store is the right answer for very
         * large retention.
         */
        @Min(value = 1, message = "delivery-events max-entries must be at least 1")
        private int maxEntries = 5_000;
    }

    /**
     * Redis-backed distributed-state config (DD-14). The per-feature
     * toggles are unset by default, so {@code notification.store.type}
     * decides which family backs each feature; set a toggle only to
     * override that choice for one feature.
     *
     * <p>Connection details default to {@code localhost:6379} so a
     * developer Docker Redis just works; production deployments
     * override via env vars or {@code spring.data.redis.*} which
     * Spring Data Redis honours alongside this config.
     */
    @Data
    public static class RedisProperties {
        /**
         * Namespace prefix for every key the module writes. Lets
         * multiple services share a Redis instance without collisions.
         */
        @NotBlank
        private String keyPrefix = "notification-svc";

        /** Idempotency-store distribution toggle (closes DD-10's foreseen-Redis SPI). */
        @Valid
        private FeatureToggle idempotency = new FeatureToggle();

        /** Rate-limit distribution toggle (closes DD-12's foreseen-Redis SPI). */
        @Valid
        private FeatureToggle rateLimit = new FeatureToggle();

        /** Dead-letter distribution toggle (closes DD-13's foreseen-Redis SPI). */
        @Valid
        private DeadLetterToggle deadLetter = new DeadLetterToggle();

        /**
         * Delivery-event distribution toggle (DD-17). Multi-pod
         * deployments turn this on so callbacks landing on different
         * pods share a single queryable buffer rather than each pod
         * holding its own view.
         */
        @Valid
        private DeliveryEventToggle deliveryEvents = new DeliveryEventToggle();

        @Data
        public static class FeatureToggle {
            /**
             * Per-feature store override: {@code true} backs this feature
             * with Redis, {@code false} keeps it in memory. Unset
             * ({@code null}, the default) follows {@code notification.store.type}.
             */
            private Boolean enabled;
        }

        @Data
        public static class DeadLetterToggle {
            /**
             * Per-feature store override: {@code true} backs dead letters
             * with Redis, {@code false} keeps them in memory. Unset
             * ({@code null}, the default) follows {@code notification.store.type}.
             */
            private Boolean enabled;

            /**
             * Maximum entries retained in the Redis LIST. Older
             * entries are LTRIM'd on every write. Mirrors the
             * in-memory {@code DeadLetterProperties.maxEntries}
             * semantics — a debug surface, not a long-term archive.
             */
            @Min(value = 1, message = "redis.dead-letter max-entries must be at least 1")
            private int maxEntries = 1_000;
        }

        @Data
        public static class DeliveryEventToggle {
            /**
             * Per-feature store override: {@code true} backs delivery events
             * with Redis, {@code false} keeps them in memory. Unset
             * ({@code null}, the default) follows {@code notification.store.type}.
             */
            private Boolean enabled;

            /**
             * Maximum events retained in the Redis LIST. Higher
             * default than the DLQ since delivery events arrive at
             * a rate proportional to send volume.
             */
            @Min(value = 1, message = "redis.delivery-events max-entries must be at least 1")
            private int maxEntries = 10_000;
        }
    }

    /**
     * Webhook callback configuration (DD-16). Master switch off by
     * default; each provider section has its own enabled flag so an
     * SES-only deployment doesn't have to provide Twilio's auth token
     * to satisfy startup validation.
     */
    @Data
    public static class WebhookProperties {
        /**
         * Master switch. When {@code false}, the webhook controller is
         * not registered — the {@code /webhooks/*} paths return 404
         * (Spring just doesn't route them). When {@code true}, each
         * per-provider sub-section governs whether that provider's
         * handler is wired up.
         */
        private boolean enabled = false;

        /**
         * Path-prefix for the webhook surface, joined with
         * {@code notification.rest.base-path}. The default
         * {@code /api/v1/webhooks/{provider}/{path}} is what most
         * deployments will end up with; operators changing this should
         * remember to update the URLs registered with each provider.
         */
        @NotBlank
        private String basePath = "/webhooks";

        /** Twilio SMS status-callback handler config. */
        @Valid
        private TwilioWebhook twilio = new TwilioWebhook();

        /** SES delivery / bounce / complaint via SNS handler config. */
        @Valid
        private SesWebhook ses = new SesWebhook();

        @Data
        public static class TwilioWebhook {
            private boolean enabled = false;

            /**
             * Twilio account auth token. Required when
             * {@code enabled=true} and
             * {@code signature-verification=true} (which is the safe
             * default). Required because the verification HMAC keys
             * off it.
             *
             * <p>Operators almost always wire this from
             * {@code ${TWILIO_AUTH_TOKEN}} so it doesn't end up in
             * application.yml.
             */
            private String authToken;

            /**
             * Verify the {@code X-Twilio-Signature} header on every
             * inbound callback. <strong>Should remain {@code true}</strong>
             * in production. Operators occasionally disable for local
             * development against tunnelled endpoints; the WARN log on
             * disable is intentional.
             */
            private boolean signatureVerification = true;
        }

        @Data
        public static class SesWebhook {
            private boolean enabled = false;

            /**
             * The SNS topic ARN that SES publishes
             * Delivery/Bounce/Complaint events to. Used as a
             * defense-in-depth check after AWS SDK signature
             * verification — we drop messages signed by AWS but
             * published to a different topic ARN, since that's a
             * misconfiguration not a forge.
             *
             * <p>{@code null} disables the topic-arn check
             * (signature still applies).
             */
            private String topicArn;

            /**
             * Verify SNS message signatures via the AWS SDK
             * {@code MessageManager.parseMessage}. <strong>Should
             * remain {@code true}</strong> — without it, anyone can
             * POST a forged Notification at the endpoint.
             */
            private boolean signatureVerification = true;
        }
    }

    /**
     * Tenant provider fallback (DD-28).
     *
     * @since 1.2.1
     */
    @Data
    public static class TenantFallbackProperties {
        /**
         * When {@code true}, a tenant with no entry under
         * {@code notification.tenants} sends through the providers of
         * {@code notification.default-tenant}. Only provider resolution falls
         * back; the request keeps its own tenant id for idempotency, rate
         * limits, templates and audit. {@code false} (the default) keeps the
         * 1.2.0 behaviour: such a tenant fails with {@code TENANT_NOT_FOUND}.
         */
        private boolean enabled = false;
    }

    /**
     * Provider lifecycle settings (DD-29).
     *
     * @since 1.2.1
     */
    @Data
    public static class ProvidersProperties {
        /**
         * When {@code true}, providers are configured and initialised on the
         * first send that needs them instead of at startup, so the
         * application boots even when a provider's settings are missing.
         * A failed build is not cached; the send fails with
         * {@code PROVIDER_NOT_CONFIGURED} ({@code TRANSIENT}) and the next
         * send tries again. {@code false} (the default) keeps the 1.2.0
         * fail-fast startup.
         */
        private boolean lazyInit = false;
    }

    @Data
    public static class TenantConfig {
        /**
         * Channel configurations for this tenant
         */
        private Map<String, ChannelConfig> channels = new LinkedHashMap<>();
    }

    @Data
    public static class ChannelConfig {
        /**
         * Whether this channel is enabled
         */
        private boolean enabled = true;

        /**
         * Channel-level configuration (shared by all providers)
         */
        private Map<String, Object> config = new LinkedHashMap<>();

        /**
         * Provider-specific configurations
         */
        private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

        /**
         * Check if provider is required in request.
         * Provider is NOT required if:
         * - Only 1 provider is configured, OR
         * - One provider has default=true
         *
         * @return true if provider must be specified in request
         */
        public boolean isProviderRequiredInRequest() {
            if (providers.size() == 1) {
                return false; // Single provider, no need to specify
            }
            // Multiple providers - check if any has default=true
            return providers.values().stream().noneMatch(ProviderConfig::isDefault);
        }

        /**
         * Get the default provider name.
         * Returns:
         * - The only provider if single provider configured
         * - The provider with default=true if multiple providers
         * - null if no default can be determined
         *
         * @return default provider name or null
         */
        public String getDefaultProviderName() {
            if (providers.size() == 1) {
                return providers.keySet().iterator().next();
            }
            // Find provider with default=true
            return providers.entrySet().stream()
                    .filter(e -> e.getValue().isDefault())
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
        }
    }

    @Data
    public static class ProviderConfig {
        /**
         * Spring bean name (for Spring-managed providers)
         */
        private String beanName;

        /**
         * Fully qualified class name (for reflection-based instantiation)
         */
        private String fqcn;

        /**
         * Whether this is the default provider for the channel.
         * Only ONE provider per channel can have default=true.
         * If only one provider is configured, it's automatically the default.
         */
        private boolean isDefault = false;

        /**
         * Provider-specific properties (passed to configure() method)
         */
        private Map<String, Object> properties = new LinkedHashMap<>();
    }
}
