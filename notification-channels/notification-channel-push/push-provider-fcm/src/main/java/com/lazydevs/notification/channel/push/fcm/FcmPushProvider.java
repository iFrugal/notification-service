package com.lazydevs.notification.channel.push.fcm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lazydevs.notification.api.channel.PushProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.delivery.DeliveryEventEmitter;
import com.lazydevs.notification.api.delivery.DeliveryEventPublisher;
import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.PushRecipient;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.util.PiiMasking;
import com.lazydevs.notification.channel.push.fcm.FcmMessageMapper.InvalidMessageException;
import com.lazydevs.notification.channel.push.fcm.FcmMessageMapper.Target;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.UUID;

/**
 * Firebase Cloud Messaging provider over the FCM HTTP v1 API.
 *
 * <p>Configured per tenant: {@code ProviderRegistry} calls {@link #configure(Map)}
 * with the tenant's provider properties (see {@link FcmSettings} for the keys),
 * then {@link #init()}.
 * Ways to obtain an instance:
 * <ul>
 *   <li>the prototype bean {@code fcmPushProvider} registered by
 *       {@link FcmPushProviderAutoConfiguration} - each lookup is a fresh instance,
 *       {@link FcmHttpTransport} and {@link FcmAccessTokenProviderFactory} beans are
 *       used, and {@code http-transport=<bean name>} can pick a transport per tenant;</li>
 *   <li>the public no-arg constructor (fqcn or reflective instantiation) - the transport
 *       and the token provider factories come from {@link ServiceLoader}, with
 *       {@link JdkFcmHttpTransport#shared()} and {@link ServiceAccountJwtTokenProviderFactory}
 *       as fallbacks;</li>
 *   <li>{@link #withTransport(FcmSettings, FcmHttpTransport, FcmAccessTokenProvider)} - a ready
 *       instance over your own transport and token provider, for tests.</li>
 * </ul>
 *
 * <p>{@code configure} parses and validates everything and reads the credentials; it fails
 * fast with a {@link ProviderConfigurationException}. Neither {@code configure} nor
 * {@code init} calls the network: the first access token is fetched by the first send.
 *
 * <p>The provider message id is FCM's message name ({@code projects/<id>/messages/<id>})
 * on success, and {@code fcm-local:<uuid>} on failure, so a failed attempt can still be
 * joined with the delivery events published for it. A device token or installation id
 * that FCM rejects as gone or foreign ({@code UNREGISTERED}, {@code SENDER_ID_MISMATCH},
 * {@code INVALID_ARGUMENT} on the token) is published as a {@code BOUNCED} delivery event
 * with reason {@code INVALID_TARGET} and the target's hash, never the target itself.
 */
@Slf4j
public class FcmPushProvider implements PushProvider, DeliveryEventEmitter {

    /** Built-in provider name ({@code PUSH:fcm}). */
    public static final String PROVIDER_NAME = FcmSettings.PROVIDER_NAME;

    /** Message id prefix of a {@code dry-run} send, which makes no HTTP call. */
    public static final String DRY_RUN_ID_PREFIX = "dry-run:";

    static final String METADATA_DRY_RUN = "fcm.dryRun";

    private final FcmHttpTransport defaultTransport;
    private final Map<String, FcmHttpTransport> namedTransports;
    private final List<FcmAccessTokenProviderFactory> factories;
    private final Clock clock;
    private final boolean preconfigured;

    private volatile DeliveryEventPublisher deliveryEventPublisher = DeliveryEventPublisher.NO_OP;

    private FcmSettings settings;
    private FcmHttpTransport transport;
    private FcmAccessTokenProvider tokenProvider;
    private String projectId;
    private FcmMultiTokenSender sender;
    private volatile boolean initialized;

    /**
     * Reflective / fqcn construction: no Spring context, so the transport and the
     * token provider factories come from {@link ServiceLoader} on this class's loader,
     * and {@code http-transport} is not available.
     */
    public FcmPushProvider() {
        this(discoverTransport(FcmPushProvider.class.getClassLoader()), List.of(),
                FcmPushProvider.class.getClassLoader());
    }

    /**
     * Construction with an explicit transport.
     *
     * @param transport   the HTTP transport; {@code null} means {@link JdkFcmHttpTransport#shared()}
     * @param factories   token provider factories asked first, in order, may be {@code null}
     * @param classLoader the loader {@link ServiceLoader} looks for further factories with
     */
    public FcmPushProvider(FcmHttpTransport transport, List<FcmAccessTokenProviderFactory> factories,
                           ClassLoader classLoader) {
        this(transport, Map.of(), factories, classLoader, Clock.systemUTC());
    }

    /**
     * Construction by {@link FcmPushProviderAutoConfiguration}.
     *
     * @param transport       the default HTTP transport; {@code null} means {@link JdkFcmHttpTransport#shared()}
     * @param namedTransports {@link FcmHttpTransport} beans by name, for {@code http-transport}
     * @param factories       token provider factories asked first, in order, may be {@code null}
     * @param classLoader     the loader {@link ServiceLoader} looks for further factories with
     */
    public FcmPushProvider(FcmHttpTransport transport, Map<String, FcmHttpTransport> namedTransports,
                           List<FcmAccessTokenProviderFactory> factories, ClassLoader classLoader) {
        this(transport, namedTransports, factories, classLoader, Clock.systemUTC());
    }

    FcmPushProvider(FcmHttpTransport transport, Map<String, FcmHttpTransport> namedTransports,
                    List<FcmAccessTokenProviderFactory> factories, ClassLoader classLoader, Clock clock) {
        this.defaultTransport = transport != null ? transport : JdkFcmHttpTransport.shared();
        this.namedTransports = namedTransports == null ? Map.of() : Map.copyOf(namedTransports);
        this.factories = mergeFactories(factories, classLoader);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.preconfigured = false;
    }

    private FcmPushProvider(FcmSettings settings, FcmHttpTransport transport, FcmAccessTokenProvider tokenProvider,
                            Clock clock) {
        this.defaultTransport = transport;
        this.namedTransports = Map.of();
        this.factories = List.of();
        this.clock = clock;
        this.preconfigured = true;
        this.settings = settings;
        this.transport = transport;
        this.tokenProvider = tokenProvider;
        this.projectId = resolveProjectId(settings, tokenProvider);
        this.sender = new FcmMultiTokenSender(settings, transport, tokenProvider, projectId, clock);
        this.initialized = true;
    }

    /**
     * A ready-to-use provider over your own transport and token provider, for testing an
     * integration without Google: point the transport at a stub, or wrap a test double.
     *
     * <p>The instance is already configured and initialised: {@link #configure(Map)} and
     * {@link #init()} are no-ops on it, so a {@code ProviderRegistry} can still call them.
     *
     * @param settings      the settings, for example from {@link FcmSettings#fromMap(Map)};
     *                      {@code project-id} is required unless the token provider names one
     * @param transport     the transport that performs the HTTP calls
     * @param tokenProvider supplies the bearer tokens
     * @return the provider
     */
    public static FcmPushProvider withTransport(FcmSettings settings, FcmHttpTransport transport,
                                                FcmAccessTokenProvider tokenProvider) {
        return withTransport(settings, transport, tokenProvider, Clock.systemUTC());
    }

    static FcmPushProvider withTransport(FcmSettings settings, FcmHttpTransport transport,
                                         FcmAccessTokenProvider tokenProvider, Clock clock) {
        return new FcmPushProvider(Objects.requireNonNull(settings, "settings"),
                Objects.requireNonNull(transport, "transport"),
                Objects.requireNonNull(tokenProvider, "tokenProvider"),
                Objects.requireNonNull(clock, "clock"));
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public void setDeliveryEventPublisher(DeliveryEventPublisher publisher) {
        this.deliveryEventPublisher = publisher == null ? DeliveryEventPublisher.NO_OP : publisher;
    }

    /**
     * Parse and validate the tenant's settings, pick the transport and create the token
     * provider (which reads and checks the credentials). No network call.
     */
    @Override
    public void configure(Map<String, Object> properties) {
        if (preconfigured) {
            log.debug("FCM push provider was built with a transport; ignoring configure(...)");
            return;
        }
        FcmSettings parsed = FcmSettings.fromMap(properties);
        if (FcmSettings.usesDeprecatedCredentialsPath(properties)) {
            log.warn("FCM push provider: '{}' is deprecated, rename it to '{}'",
                    FcmSettings.CREDENTIALS_PATH, FcmSettings.CREDENTIALS);
        }
        if (!FcmSettings.hasText(parsed.credentials())) {
            throw FcmSettings.invalid("'" + FcmSettings.CREDENTIALS + "' is required: the path of a service-account"
                    + " JSON key, the inline JSON, '" + FcmSettings.CREDENTIALS_ADC + "' or '"
                    + FcmSettings.CREDENTIALS_EXTERNAL_ACCOUNT + "<path>'");
        }
        FcmHttpTransport selected = selectTransport(parsed);
        FcmAccessTokenProviderFactory factory = factoryFor(parsed.credentials());
        FcmAccessTokenProvider created;
        try {
            created = factory.create(parsed, selected, clock);
        } catch (ProviderConfigurationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw FcmSettings.invalid("cannot use the credentials " + FcmSettings.describeCredentials(parsed.credentials())
                    + ": " + e.getClass().getSimpleName());
        }
        String resolvedProjectId;
        try {
            resolvedProjectId = resolveProjectId(parsed, created);
        } catch (ProviderConfigurationException e) {
            closeQuietly(created);
            throw e;
        }
        // A re-configured instance must not keep the token provider of the old settings.
        closeQuietly(this.tokenProvider);
        this.settings = parsed;
        this.transport = selected;
        this.tokenProvider = created;
        this.projectId = resolvedProjectId;
        this.sender = null;
        this.initialized = false;
        log.debug("FCM push provider configured: {}", parsed);
    }

    /**
     * Build the sender. No network call happens here.
     */
    @Override
    public void init() {
        if (settings == null) {
            throw new IllegalStateException("FcmPushProvider.configure(...) must be called before init()");
        }
        if (initialized) {
            return;
        }
        this.sender = new FcmMultiTokenSender(settings, transport, tokenProvider, projectId, clock);
        this.initialized = true;
        log.info("FCM push provider initialized: projectId={}, endpoint={}, credentials={}, transport={},"
                        + " concurrency={}, validateOnly={}, dryRun={}",
                projectId, settings.endpoint(), credentialKind(), transport.getClass().getSimpleName(),
                settings.concurrency(), settings.validateOnly(), settings.dryRun());
    }

    @Override
    public void destroy() {
        initialized = false;
        closeQuietly(tokenProvider);
    }

    @Override
    public boolean isHealthy() {
        // FCM has no free read-only probe; report whether init() completed.
        return initialized;
    }

    /** No secrets: the project, endpoint, credential kind, transport and limits. */
    @Override
    public Map<String, Object> getHealthDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", isHealthy() ? "UP" : "DOWN");
        if (settings != null) {
            details.put("projectId", projectId);
            details.put("endpoint", settings.endpoint().toString());
            details.put("credentials", credentialKind());
            details.put("transport", transport == null ? null : transport.getClass().getSimpleName());
            details.put("concurrency", settings.concurrency());
            FcmMultiTokenSender current = sender;
            if (current != null) {
                details.put("availablePermits", current.availablePermits());
            }
            details.put("multiTokenPolicy", settings.multiTokenPolicy().name().toLowerCase(Locale.ROOT));
            details.put("validateOnly", settings.validateOnly());
            details.put("dryRun", settings.dryRun());
        }
        return details;
    }

    @Override
    public SendResult send(NotificationRequest request, RenderedContent content) {
        // Read the volatile flag first: init() publishes the sender and settings before setting it.
        FcmMultiTokenSender current = initialized ? sender : null;
        if (current == null) {
            throw new IllegalStateException("FcmPushProvider is not initialized; call configure(...) and init()");
        }
        if (!(request.getRecipient() instanceof PushRecipient recipient)) {
            return SendResult.failure(FcmMessageMapper.CODE_INVALID_TARGET_SPEC,
                    "the FCM provider needs a PUSH recipient", FailureType.PERMANENT);
        }
        FcmMessageMapper mapper = new FcmMessageMapper(settings);
        Target target;
        ObjectNode message;
        try {
            target = mapper.target(recipient);
            message = mapper.message(request, content, recipient, target);
        } catch (InvalidMessageException e) {
            log.warn("FCM message rejected before sending: {} code={}: {}", describe(recipient), e.code(),
                    PiiMasking.redact(e.getMessage()));
            return SendResult.failure(e.code(), PiiMasking.redact(e.getMessage()), FailureType.PERMANENT);
        }
        if (settings.dryRun()) {
            String id = DRY_RUN_ID_PREFIX + UUID.randomUUID();
            log.info("FCM dry run, nothing sent: {}, messageId={}", describe(recipient), id);
            return SendResult.success(id, Map.of(METADATA_DRY_RUN, true));
        }
        return target.multi()
                ? current.sendMulti(message, target, deliveryEventPublisher)
                : current.sendSingle(message, target, deliveryEventPublisher);
    }

    /**
     * The recipient's target for a log line: a device token or installation id only as
     * its hash (or {@code (hidden)}), several tokens as a count.
     */
    private String describe(PushRecipient recipient) {
        if (FcmSettings.hasText(recipient.deviceToken())) {
            return "token=" + hashOrHidden(recipient.deviceToken());
        }
        if (recipient.deviceTokens() != null && !recipient.deviceTokens().isEmpty()) {
            return "tokens=" + recipient.deviceTokens().size();
        }
        if (FcmSettings.hasText(recipient.fid())) {
            return "fid=" + hashOrHidden(recipient.fid());
        }
        if (FcmSettings.hasText(recipient.topic())) {
            return "topic=" + recipient.topic().strip();
        }
        return FcmSettings.hasText(recipient.condition()) ? "condition=(set)" : "target=(none)";
    }

    private String hashOrHidden(String value) {
        return settings.logTokenHash() ? FcmJson.targetHash(value.strip()) : "(hidden)";
    }

    private FcmHttpTransport selectTransport(FcmSettings parsed) {
        String name = parsed.httpTransport();
        if (name == null) {
            return defaultTransport;
        }
        FcmHttpTransport named = namedTransports.get(name);
        if (named == null) {
            throw FcmSettings.invalid(namedTransports.isEmpty()
                    ? "'" + FcmSettings.HTTP_TRANSPORT + "' names the bean '" + name + "', but this provider has no"
                    + " FcmHttpTransport beans; it needs the Spring bean 'fcmPushProvider' (not fqcn) and a bean of"
                    + " that name"
                    : "no FcmHttpTransport bean named '" + name + "'; available: " + namedTransports.keySet());
        }
        return named;
    }

    private FcmAccessTokenProviderFactory factoryFor(String credentials) {
        for (FcmAccessTokenProviderFactory factory : factories) {
            if (factory.supports(credentials)) {
                return factory;
            }
        }
        if (FcmSettings.needsGoogleAuth(credentials)) {
            throw FcmSettings.invalid("credentials '" + FcmSettings.describeCredentials(credentials)
                    + "' needs the module " + FcmSettings.GOOGLE_AUTH_ARTIFACT + " on the classpath (same version"
                    + " as push-provider-fcm); without it, use a service-account JSON key file or inline JSON");
        }
        throw FcmSettings.invalid("no FcmAccessTokenProviderFactory supports the credentials "
                + FcmSettings.describeCredentials(credentials));
    }

    private static String resolveProjectId(FcmSettings settings, FcmAccessTokenProvider tokenProvider) {
        if (settings.projectId() != null) {
            return settings.projectId();
        }
        return tokenProvider.projectId()
                .filter(FcmSettings::hasText)
                .orElseThrow(() -> FcmSettings.invalid("'" + FcmSettings.PROJECT_ID + "' is required: the"
                        + " credentials do not name a Firebase project"));
    }

    private String credentialKind() {
        if (settings == null) {
            return null;
        }
        String described = FcmSettings.describeCredentials(settings.credentials());
        return described != null ? described : tokenProvider == null ? null : tokenProvider.getClass().getSimpleName();
    }

    private static void closeQuietly(FcmAccessTokenProvider provider) {
        if (provider == null) {
            return;
        }
        try {
            provider.close();
        } catch (RuntimeException e) {
            log.warn("Closing the FCM access token provider failed: {}", e.getClass().getSimpleName());
        }
    }

    private static FcmHttpTransport discoverTransport(ClassLoader classLoader) {
        return ServiceLoader.load(FcmHttpTransport.class, classLoader).findFirst()
                .orElseGet(JdkFcmHttpTransport::shared);
    }

    /**
     * The explicit factories, then the {@link ServiceLoader} ones, then the built-in
     * service-account factory; one instance per class, the first wins.
     */
    static List<FcmAccessTokenProviderFactory> mergeFactories(List<FcmAccessTokenProviderFactory> explicit,
                                                              ClassLoader classLoader) {
        List<FcmAccessTokenProviderFactory> merged = new ArrayList<>();
        if (explicit != null) {
            explicit.stream().filter(Objects::nonNull).forEach(merged::add);
        }
        ClassLoader loader = classLoader != null ? classLoader : FcmPushProvider.class.getClassLoader();
        ServiceLoader.load(FcmAccessTokenProviderFactory.class, loader).forEach(merged::add);
        merged.add(new ServiceAccountJwtTokenProviderFactory());
        List<FcmAccessTokenProviderFactory> unique = new ArrayList<>();
        for (FcmAccessTokenProviderFactory factory : merged) {
            if (unique.stream().noneMatch(f -> f.getClass() == factory.getClass())) {
                unique.add(factory);
            }
        }
        return List.copyOf(unique);
    }

    // Package-private accessors for tests.

    FcmSettings settings() {
        return settings;
    }

    FcmHttpTransport transport() {
        return transport;
    }

    FcmAccessTokenProvider tokenProvider() {
        return tokenProvider;
    }

    List<FcmAccessTokenProviderFactory> factories() {
        return factories;
    }

    String projectId() {
        return projectId;
    }

    DeliveryEventPublisher deliveryEventPublisher() {
        return deliveryEventPublisher;
    }
}
