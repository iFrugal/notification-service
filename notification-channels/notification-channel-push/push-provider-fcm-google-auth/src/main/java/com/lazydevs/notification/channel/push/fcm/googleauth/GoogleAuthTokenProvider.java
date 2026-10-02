package com.lazydevs.notification.channel.push.fcm.googleauth;

import com.google.api.client.http.HttpResponseException;
import com.google.auth.Retryable;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.ExternalAccountCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.FailureTypes;
import com.lazydevs.notification.channel.push.fcm.FcmAccessToken;
import com.lazydevs.notification.channel.push.fcm.FcmAccessTokenProvider;
import com.lazydevs.notification.channel.push.fcm.FcmAuthenticationException;
import com.lazydevs.notification.channel.push.fcm.FcmHttpRequest;
import com.lazydevs.notification.channel.push.fcm.FcmHttpResponse;
import com.lazydevs.notification.channel.push.fcm.FcmHttpTransport;
import com.lazydevs.notification.channel.push.fcm.FcmPushProvider;
import com.lazydevs.notification.channel.push.fcm.FcmSettings;
import com.lazydevs.notification.channel.push.fcm.FcmTransportException;
import com.lazydevs.notification.channel.push.fcm.ServiceAccountJwtTokenProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * FCM access tokens from the Google auth library: Application Default Credentials
 * ({@code credentials: adc}) or a workload identity federation configuration
 * ({@code credentials: external-account:<path>}).
 *
 * <ul>
 *   <li>The credentials are loaded with the type-specific loaders
 *       ({@link GoogleCredentials#getApplicationDefault(HttpTransportFactory)},
 *       {@link ExternalAccountCredentials#fromStream(java.io.InputStream, HttpTransportFactory)})
 *       and scoped to {@value ServiceAccountJwtTokenProvider#SCOPE}.</li>
 *   <li>Every HTTP call of the library goes through the tenant's {@link FcmHttpTransport},
 *       wrapped in an {@link FcmHttpTransportAdapter}.</li>
 *   <li>The token is cached until {@code token-refresh-margin} before it expires, judged
 *       with the provider's clock; then, and after {@link #invalidate()}, the next
 *       {@link #token()} calls {@link GoogleCredentials#refresh()}. Concurrent callers wait
 *       for one refresh.</li>
 *   <li>Failures: no response from an endpoint, 408, 425, 429 and 5xx are
 *       {@link FailureType#TRANSIENT}; any other 4xx is {@link FailureType#PERMANENT};
 *       anything else is {@link FailureType#UNKNOWN}. The library itself retries a
 *       service-account token call on 408, 429, 500 and 503 a few times first.</li>
 * </ul>
 *
 * <p>No access token, subject token or key is ever logged; error messages carry the
 * library's description of the failure, which names endpoints and OAuth error codes only.
 *
 * @since 1.2.0
 */
@Slf4j
public final class GoogleAuthTokenProvider implements FcmAccessTokenProvider {

    /** Assumed lifetime of a token the library returns without an expiry: Google's default. */
    static final Duration ASSUMED_LIFETIME = Duration.ofHours(1);

    private static final int MAX_MESSAGE = 300;

    private final String description;
    private final GoogleCredentials credentials;
    private final ObservedTransport observed;
    private final FcmHttpTransportAdapter adapter;
    private final boolean bindPerCall;
    private final String projectId;
    private final Clock clock;
    private final Duration refreshMargin;

    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile Cached cached;

    private record Cached(FcmAccessToken token, Instant refreshAt) {
    }

    /** Loads unscoped credentials with the transport factory it is given. */
    @FunctionalInterface
    interface CredentialsLoader {
        GoogleCredentials load(HttpTransportFactory transportFactory) throws IOException;
    }

    private GoogleAuthTokenProvider(String description, GoogleCredentials credentials, ObservedTransport observed,
                                    FcmHttpTransportAdapter adapter, boolean bindPerCall, String projectId,
                                    Clock clock, Duration refreshMargin) {
        this.description = description;
        this.credentials = credentials;
        this.observed = observed;
        this.adapter = adapter;
        this.bindPerCall = bindPerCall;
        this.projectId = projectId;
        this.clock = clock;
        this.refreshMargin = refreshMargin;
    }

    /**
     * Application Default Credentials, resolved now: {@code GOOGLE_APPLICATION_CREDENTIALS},
     * the gcloud well-known file, then the Google Cloud runtime (metadata server).
     * Resolving reads the credential file and, only when there is none, probes the
     * metadata server through {@code transport}; no token is fetched until the first
     * {@link #token()}.
     *
     * @param transport     the tenant's transport; every token call goes through it
     * @param clock         the clock to judge token expiry with
     * @param refreshMargin refresh this long before the token expires
     * @param timeout       the longest timeout of one HTTP call
     * @return the provider
     * @throws ProviderConfigurationException when no credentials are found or they are unusable
     */
    public static GoogleAuthTokenProvider applicationDefault(FcmHttpTransport transport, Clock clock,
                                                             Duration refreshMargin, Duration timeout) {
        return load(FcmSettings.CREDENTIALS_ADC, true, transport, clock, refreshMargin, timeout,
                _ -> GoogleCredentials.getApplicationDefault(ScopedTransportFactory.INSTANCE),
                "Application Default Credentials are not available",
                "; set GOOGLE_APPLICATION_CREDENTIALS, run 'gcloud auth application-default login',"
                        + " or run on Google Cloud with an attached service account");
    }

    /**
     * Workload identity federation: an {@code external_account} credential configuration
     * file, as written by {@code gcloud iam workload-identity-pools create-cred-config}
     * (file-, URL- or executable-sourced subject tokens, AWS, with or without service
     * account impersonation). Reads and parses the file; no network call.
     *
     * @param config        the configuration file
     * @param transport     the tenant's transport; every token call goes through it
     * @param clock         the clock to judge token expiry with
     * @param refreshMargin refresh this long before the token expires
     * @param timeout       the longest timeout of one HTTP call
     * @return the provider
     * @throws ProviderConfigurationException when the file cannot be read or is not an
     *                                        {@code external_account} configuration
     */
    public static GoogleAuthTokenProvider externalAccount(Path config, FcmHttpTransport transport, Clock clock,
                                                          Duration refreshMargin, Duration timeout) {
        Objects.requireNonNull(config, "config");
        String spec = FcmSettings.CREDENTIALS_EXTERNAL_ACCOUNT + config;
        byte[] json;
        try {
            json = Files.readAllBytes(config);
        } catch (IOException | RuntimeException e) {
            throw invalid("cannot read the external account configuration '" + config + "' ("
                    + e.getClass().getSimpleName() + ")");
        }
        return load(spec, false, transport, clock, refreshMargin, timeout,
                factory -> ExternalAccountCredentials.fromStream(new ByteArrayInputStream(json), factory),
                "the external account configuration '" + config + "' is not usable",
                "; it must be an 'external_account' credential configuration");
    }

    /**
     * Credentials built by the caller, for tests: {@code loader} is called with a factory
     * whose transport is the tenant's.
     */
    static GoogleAuthTokenProvider of(String description, FcmHttpTransport transport, Clock clock,
                                      Duration refreshMargin, Duration timeout, CredentialsLoader loader) {
        return load(description, false, transport, clock, refreshMargin, timeout, loader,
                "the credentials '" + description + "' are not usable", "");
    }

    private static GoogleAuthTokenProvider load(String description, boolean bindPerCall, FcmHttpTransport transport,
                                                Clock clock, Duration refreshMargin, Duration timeout,
                                                CredentialsLoader loader, String failure, String hint) {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(refreshMargin, "refreshMargin");
        Objects.requireNonNull(timeout, "timeout");
        ObservedTransport observed = new ObservedTransport(transport);
        FcmHttpTransportAdapter adapter = new FcmHttpTransportAdapter(observed, timeout);
        GoogleCredentials loaded;
        try {
            loaded = ScopedValue.where(ScopedTransportFactory.CURRENT, adapter).call(() -> loader.load(() -> adapter));
        } catch (IOException | RuntimeException e) {
            throw invalid(failure + ": " + describe(e) + hint);
        }
        GoogleCredentials scoped = loaded.createScoped(ServiceAccountJwtTokenProvider.SCOPE);
        return new GoogleAuthTokenProvider(description, scoped, observed, adapter, bindPerCall,
                projectIdOf(loaded), clock, refreshMargin);
    }

    /** The project a service account belongs to, else the quota project; never a network call. */
    private static String projectIdOf(GoogleCredentials credentials) {
        if (credentials instanceof ServiceAccountCredentials serviceAccount
                && hasText(serviceAccount.getProjectId())) {
            return serviceAccount.getProjectId();
        }
        String quotaProject = credentials.getQuotaProjectId();
        return hasText(quotaProject) ? quotaProject : null;
    }

    @Override
    public FcmAccessToken token() {
        Cached current = cached;
        if (current != null && clock.instant().isBefore(current.refreshAt())) {
            return current.token();
        }
        refreshLock.lock();
        try {
            // Another caller may have refreshed while this one waited.
            current = cached;
            if (current != null && clock.instant().isBefore(current.refreshAt())) {
                return current.token();
            }
            Cached fresh = fetch();
            cached = fresh;
            return fresh.token();
        } finally {
            refreshLock.unlock();
        }
    }

    @Override
    public void invalidate() {
        cached = null;
    }

    @Override
    public Optional<String> projectId() {
        return Optional.ofNullable(projectId);
    }

    /** Nothing to release: the credentials hold no connection, and the transport is not owned. */
    @Override
    public void close() {
        cached = null;
    }

    /** @return the scoped credentials, for tests */
    GoogleCredentials credentials() {
        return credentials;
    }

    private Cached fetch() {
        Instant now = clock.instant();
        observed.reset();
        AccessToken token;
        try {
            if (bindPerCall) {
                ScopedValue.where(ScopedTransportFactory.CURRENT, adapter).call(() -> {
                    credentials.refresh();
                    return null;
                });
            } else {
                credentials.refresh();
            }
            token = credentials.getAccessToken();
        } catch (IOException e) {
            throw failure(e);
        } catch (RuntimeException e) {
            throw new FcmAuthenticationException("FCM access token from " + description + " credentials failed: "
                    + describe(e), FailureType.UNKNOWN, e);
        }
        if (token == null || !hasText(token.getTokenValue())) {
            throw new FcmAuthenticationException("FCM access token from " + description
                    + " credentials: the Google auth library returned no token", FailureType.UNKNOWN, null);
        }
        Instant expiresAt = token.getExpirationTime() == null
                ? now.plus(ASSUMED_LIFETIME)
                : token.getExpirationTime().toInstant();
        Duration lifetime = Duration.between(now, expiresAt);
        if (lifetime.isNegative()) {
            lifetime = Duration.ZERO;
        }
        // A margin longer than half the lifetime would refresh on nearly every call.
        Duration margin = refreshMargin.compareTo(lifetime.dividedBy(2)) > 0 ? lifetime.dividedBy(2) : refreshMargin;
        log.debug("Fetched an FCM access token with {} credentials, expires at {}", description, expiresAt);
        return new Cached(new FcmAccessToken(token.getTokenValue(), expiresAt), expiresAt.minus(margin));
    }

    private FcmAuthenticationException failure(IOException e) {
        String prefix = "FCM access token from " + description + " credentials failed";
        FcmTransportException noAnswer = cause(e, FcmTransportException.class);
        if (noAnswer != null) {
            // The token call carries no notification, so a missing answer is always safe to retry.
            return new FcmAuthenticationException(prefix + ": the endpoint did not answer: " + describe(e),
                    FailureType.TRANSIENT, e);
        }
        int status = observed.lastErrorStatus();
        HttpResponseException httpError = cause(e, HttpResponseException.class);
        if (status == 0 && httpError != null) {
            status = httpError.getStatusCode();
        }
        FailureType type;
        if (status > 0) {
            if (FailureTypes.fromHttpStatus(status) == FailureType.TRANSIENT) {
                type = FailureType.TRANSIENT;
            } else if (status >= 400 && status < 500) {
                type = FailureType.PERMANENT;
            } else {
                type = FailureType.UNKNOWN;
            }
        } else {
            Retryable retryable = cause(e, Retryable.class);
            type = retryable != null && retryable.isRetryable() ? FailureType.TRANSIENT : FailureType.UNKNOWN;
        }
        String message = prefix + (status > 0 ? ": HTTP " + status : "") + ": " + describe(e);
        if (type == FailureType.PERMANENT) {
            message += "; check the credential configuration and the IAM bindings of the identity";
        }
        return new FcmAuthenticationException(message, type, e);
    }

    private static <T> T cause(Throwable t, Class<T> type) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    /** The messages of the cause chain that add something, on one line, truncated. */
    static String describe(Throwable t) {
        Set<String> messages = new LinkedHashSet<>();
        int depth = 0;
        for (Throwable current = t; current != null && depth < 4; current = current.getCause(), depth++) {
            String message = current.getMessage();
            String text = hasText(message)
                    ? message.replaceAll("\\s+", " ").strip()
                    : current.getClass().getSimpleName();
            if (messages.stream().noneMatch(earlier -> earlier.contains(text))) {
                messages.add(text);
            }
        }
        String joined = String.join(" / ", messages);
        return joined.length() <= MAX_MESSAGE ? joined : joined.substring(0, MAX_MESSAGE) + "...";
    }

    static ProviderConfigurationException invalid(String reason) {
        return new ProviderConfigurationException(FcmPushProvider.PROVIDER_NAME, Channel.PUSH.name(), reason);
    }

    static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    @Override
    public String toString() {
        return "GoogleAuthTokenProvider[" + description + "]";
    }

    /** Remembers the status of the last non-2xx answer of a token fetch, for the classification. */
    private static final class ObservedTransport implements FcmHttpTransport {

        private final FcmHttpTransport delegate;
        private volatile int lastErrorStatus;

        private ObservedTransport(FcmHttpTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public FcmHttpResponse execute(FcmHttpRequest request) throws FcmTransportException {
            FcmHttpResponse response = delegate.execute(request);
            if (!response.isSuccess()) {
                lastErrorStatus = response.statusCode();
            }
            return response;
        }

        int lastErrorStatus() {
            return lastErrorStatus;
        }

        void reset() {
            lastErrorStatus = 0;
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
