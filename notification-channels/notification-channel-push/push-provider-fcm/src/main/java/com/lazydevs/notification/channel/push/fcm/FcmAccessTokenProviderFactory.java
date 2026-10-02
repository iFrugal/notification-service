package com.lazydevs.notification.channel.push.fcm;

import java.time.Clock;

/**
 * Creates the {@link FcmAccessTokenProvider} for a tenant's {@code credentials} value.
 *
 * <p>{@link FcmPushProvider} asks every known factory, in order, and uses the
 * first that {@link #supports(String) supports} the value:
 * <ol>
 *   <li>Spring beans of this type (auto-configured {@code fcmPushProvider} only);</li>
 *   <li>{@code META-INF/services/com.lazydevs.notification.channel.push.fcm.FcmAccessTokenProviderFactory}
 *       entries, found with {@link java.util.ServiceLoader};</li>
 *   <li>the built-in {@link ServiceAccountJwtTokenProviderFactory}, which handles a
 *       service-account JSON file path or inline JSON.</li>
 * </ol>
 *
 * @since 1.2.0
 */
public interface FcmAccessTokenProviderFactory {

    /**
     * @param credentials the tenant's {@code credentials} value, never blank; for
     *                    example a file path, inline JSON, {@code adc} or
     *                    {@code external-account:<path>}
     * @return whether this factory can create a provider for it
     */
    boolean supports(String credentials);

    /**
     * Create the token provider. Must not call the network: the provider's
     * {@code configure()} and {@code init()} make no network call.
     *
     * @param credentials the tenant's {@code credentials} value, one this factory supports
     * @param transport   the transport to fetch tokens through
     * @param clock       the clock to judge token expiry with
     * @return the token provider
     * @throws com.lazydevs.notification.api.exception.ProviderConfigurationException
     *         when the credentials are unusable
     */
    FcmAccessTokenProvider create(String credentials, FcmHttpTransport transport, Clock clock);

    /**
     * Create the token provider with access to every tenant setting, such as
     * {@code token-endpoint} and {@code token-refresh-margin}.
     * The default delegates to {@link #create(String, FcmHttpTransport, Clock)}.
     *
     * @param settings  the tenant's settings; {@link FcmSettings#credentials()} is supported
     * @param transport the transport to fetch tokens through
     * @param clock     the clock to judge token expiry with
     * @return the token provider
     */
    default FcmAccessTokenProvider create(FcmSettings settings, FcmHttpTransport transport, Clock clock) {
        return create(settings.credentials(), transport, clock);
    }
}
