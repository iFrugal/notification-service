package com.lazydevs.notification.channel.email.acs;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpClientProvider;
import com.azure.core.http.policy.ExponentialBackoffOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.identity.DefaultAzureCredentialBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.TreeSet;
import java.util.function.Supplier;

import static com.lazydevs.notification.channel.email.acs.AcsEmailProperties.CONNECTION_STRING;
import static com.lazydevs.notification.channel.email.acs.AcsEmailProperties.CREDENTIAL;
import static com.lazydevs.notification.channel.email.acs.AcsEmailProperties.ENDPOINT;
import static com.lazydevs.notification.channel.email.acs.AcsEmailProperties.hasText;
import static com.lazydevs.notification.channel.email.acs.AcsEmailProperties.invalid;

/**
 * Builds the ACS {@link EmailClient} for one provider instance.
 *
 * <p>Authentication, in order of precedence:
 * <ol>
 *   <li>{@code connection-string} - endpoint and access key in one value.</li>
 *   <li>{@code endpoint} with {@code credential=default} - {@code DefaultAzureCredential}
 *       (managed identity, workload identity, environment, Azure CLI); needs the
 *       optional {@code com.azure:azure-identity} dependency.</li>
 *   <li>{@code endpoint} with {@code credential=<bean name>} - a {@link TokenCredential}
 *       bean from the application context.</li>
 *   <li>{@code endpoint} without {@code credential} - the application's only
 *       {@link TokenCredential} bean, if there is exactly one.</li>
 * </ol>
 * Anything else fails fast with a {@code ProviderConfigurationException}.
 *
 * <p>The module pins no Azure HTTP client implementation. azure-core discovers
 * one through {@link ServiceLoader} ({@link HttpClientProvider}); the application
 * adds exactly one, or picks one with {@code AZURE_HTTP_CLIENT_IMPLEMENTATION}
 * when several are present. Without any, {@link #createClient} fails fast with
 * {@link #NO_HTTP_CLIENT_MESSAGE}. All ACS clients in one JVM share one
 * {@link HttpClient}, and the SDK retry policy is limited to {@code sdk-retries}
 * (default 0), so the library's {@code RetryExecutor} is the single retry authority.
 */
@Slf4j
public class AcsEmailClientFactory {

    /** {@code credential} value selecting {@code DefaultAzureCredential}. */
    public static final String DEFAULT_CREDENTIAL = "default";

    static final String AZURE_IDENTITY_CLASS = "com.azure.identity.DefaultAzureCredentialBuilder";

    /** Thrown as an {@link IllegalStateException} when no {@link HttpClientProvider} is loadable. */
    static final String NO_HTTP_CLIENT_MESSAGE = "No Azure HTTP client is on the classpath."
            + " email-provider-acs does not pin one; add exactly one of"
            + " com.azure:azure-core-http-jdk-httpclient (recommended on Spring Boot 4, no Netty),"
            + " com.azure:azure-core-http-okhttp, com.azure:azure-core-http-vertx or"
            + " com.azure:azure-core-http-netty (note: pins Netty 4.1, which conflicts with"
            + " Spring Boot 4.1's Netty 4.2). When more than one is present, set"
            + " AZURE_HTTP_CLIENT_IMPLEMENTATION to choose.";

    /** Created on first use by azure-core's provider discovery; see {@link #sharedHttpClient()}. */
    private static volatile HttpClient sharedHttpClient;

    private final Map<String, TokenCredential> namedCredentials;
    private final ClassLoader classLoader;
    private final Supplier<EmailClientBuilder> builderSupplier;

    /**
     * @param namedCredentials {@link TokenCredential} beans by bean name, or {@code null}
     *                         when the provider was created outside a Spring context
     *                         (fqcn or reflective instantiation)
     * @param classLoader      used to detect azure-identity and an Azure HTTP client provider
     */
    public AcsEmailClientFactory(Map<String, TokenCredential> namedCredentials, ClassLoader classLoader) {
        this(namedCredentials, classLoader, EmailClientBuilder::new);
    }

    AcsEmailClientFactory(Map<String, TokenCredential> namedCredentials, ClassLoader classLoader,
                          Supplier<EmailClientBuilder> builderSupplier) {
        this.namedCredentials = namedCredentials == null ? null : Map.copyOf(namedCredentials);
        this.classLoader = classLoader;
        this.builderSupplier = Objects.requireNonNull(builderSupplier, "builderSupplier");
    }

    /**
     * Resolve the token credential the settings ask for.
     *
     * @param settings validated settings
     * @return the credential, or {@code null} when the connection string is used
     * @throws com.lazydevs.notification.api.exception.ProviderConfigurationException
     *         when the requested credential is not available
     */
    public TokenCredential resolveCredential(AcsEmailProperties settings) {
        if (settings.hasConnectionString()) {
            if (hasText(settings.endpoint()) || hasText(settings.credential())) {
                log.info("ACS: '{}' is set, ignoring '{}' and '{}'", CONNECTION_STRING, ENDPOINT, CREDENTIAL);
            }
            return null;
        }
        String name = settings.credential();
        if (!hasText(name)) {
            if (namedCredentials != null && namedCredentials.size() == 1) {
                return namedCredentials.values().iterator().next();
            }
            throw invalid("'" + ENDPOINT + "' is set but no credential was selected: set '" + CREDENTIAL
                    + "' to 'default' (DefaultAzureCredential, needs com.azure:azure-identity) or to the name"
                    + " of a TokenCredential bean, or use '" + CONNECTION_STRING + "' instead");
        }
        if (DEFAULT_CREDENTIAL.equalsIgnoreCase(name)) {
            if (!isAzureIdentityPresent()) {
                throw invalid("'" + CREDENTIAL + "=default' needs com.azure:azure-identity on the classpath"
                        + " (it is an optional dependency of email-provider-acs); add it, or use '"
                        + CONNECTION_STRING + "' or a TokenCredential bean");
            }
            return AzureIdentity.defaultCredential();
        }
        if (namedCredentials == null) {
            throw invalid("'" + CREDENTIAL + "=" + name + "' names a TokenCredential bean, but this provider"
                    + " instance was created outside the Spring context (fqcn or reflection); configure the"
                    + " provider with 'bean-name: acsEmailProvider', or use '" + CREDENTIAL + "=default' or '"
                    + CONNECTION_STRING + "'");
        }
        TokenCredential credential = namedCredentials.get(name);
        if (credential == null) {
            throw invalid("no TokenCredential bean named '" + name + "'; available: "
                    + new TreeSet<>(namedCredentials.keySet()));
        }
        return credential;
    }

    /**
     * Build the client.
     *
     * @param settings   validated settings
     * @param credential the result of {@link #resolveCredential}; ignored with a connection string
     * @return a ready client (no network call happens here)
     * @throws IllegalStateException when no Azure HTTP client implementation is on the classpath
     */
    public EmailClient createClient(AcsEmailProperties settings, TokenCredential credential) {
        requireHttpClientProvider(classLoader);
        EmailClientBuilder builder = builderSupplier.get()
                .httpClient(sharedHttpClient())
                .retryOptions(new RetryOptions(
                        new ExponentialBackoffOptions().setMaxRetries(settings.sdkRetries())));
        if (settings.hasConnectionString()) {
            try {
                builder.connectionString(settings.connectionString());
            } catch (RuntimeException _) {
                // The SDK's message can echo the value, which embeds the access key.
                throw invalid("'" + CONNECTION_STRING + "' is not a valid ACS connection string"
                        + " (expected endpoint=https://<resource>.communication.azure.com/;accesskey=<key>)");
            }
        } else {
            if (credential == null) {
                throw invalid("'" + ENDPOINT + "' needs a credential; call resolveCredential first");
            }
            builder.endpoint(settings.endpoint()).credential(credential);
        }
        try {
            return builder.buildClient();
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException e) {
            throw invalid("could not build the ACS EmailClient: " + e.getMessage());
        }
    }

    private boolean isAzureIdentityPresent() {
        try {
            Class.forName(AZURE_IDENTITY_CLASS, false, classLoader);
            return true;
        } catch (ClassNotFoundException | LinkageError _) {
            return false;
        }
    }

    /**
     * Only loaded after {@link #isAzureIdentityPresent()} returned {@code true},
     * so the optional azure-identity classes are never touched when absent.
     */
    private static final class AzureIdentity {
        private AzureIdentity() {
        }

        static TokenCredential defaultCredential() {
            return new DefaultAzureCredentialBuilder().build();
        }
    }

    /**
     * Fail fast, with a message that names the options, when azure-core would find
     * no {@link HttpClientProvider}; its own message only suggests Netty or OkHttp.
     */
    static void requireHttpClientProvider(ClassLoader classLoader) {
        if (!isHttpClientProviderPresent(classLoader)) {
            throw new IllegalStateException(NO_HTTP_CLIENT_MESSAGE);
        }
    }

    /**
     * Whether {@link ServiceLoader} finds a loadable {@link HttpClientProvider}, the
     * same lookup azure-core's {@code com.azure.core.implementation.util.Providers}
     * performs. A provider that is listed in {@code META-INF/services} but cannot be
     * loaded is skipped, as azure-core skips it.
     */
    static boolean isHttpClientProviderPresent(ClassLoader classLoader) {
        ClassLoader loader = classLoader != null ? classLoader : HttpClientProvider.class.getClassLoader();
        Iterator<HttpClientProvider> providers = ServiceLoader.load(HttpClientProvider.class, loader).iterator();
        while (true) {
            try {
                if (!providers.hasNext()) {
                    return false;
                }
                providers.next();
                return true;
            } catch (ServiceConfigurationError | LinkageError _) {
                // Listed but not loadable; the iterator has already moved past it.
            }
        }
    }

    /**
     * One transport shared by every ACS client, created by azure-core's default
     * discovery ({@link HttpClient#createDefault()}), so the implementation is the
     * application's choice. Azure HTTP clients are thread-safe and pool connections.
     */
    private static HttpClient sharedHttpClient() {
        HttpClient client = sharedHttpClient;
        if (client == null) {
            synchronized (AcsEmailClientFactory.class) {
                client = sharedHttpClient;
                if (client == null) {
                    try {
                        client = HttpClient.createDefault();
                    } catch (IllegalStateException e) {
                        // e.g. AZURE_HTTP_CLIENT_IMPLEMENTATION names a provider that is not present
                        throw invalid("could not create the Azure HTTP client: " + e.getMessage());
                    }
                    sharedHttpClient = client;
                }
            }
        }
        return client;
    }
}
