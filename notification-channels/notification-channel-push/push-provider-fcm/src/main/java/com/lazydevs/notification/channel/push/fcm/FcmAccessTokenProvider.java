package com.lazydevs.notification.channel.push.fcm;

import java.util.Optional;

/**
 * Supplies the OAuth 2.0 access tokens {@link FcmPushProvider} sends with.
 *
 * <p>One instance belongs to one provider instance (one tenant) and is called
 * from concurrent sends, so implementations must be thread-safe and should
 * cache the token until shortly before it expires.
 * The built-in implementation is {@link ServiceAccountJwtTokenProvider}; the
 * optional module {@code com.github.ifrugal:push-provider-fcm-google-auth}
 * adds Application Default Credentials and workload identity federation.
 *
 * @since 1.2.0
 * @see FcmAccessTokenProviderFactory
 */
public interface FcmAccessTokenProvider extends AutoCloseable {

    /**
     * A valid access token, fetched or refreshed when needed.
     *
     * @return the token
     * @throws FcmAuthenticationException when no token can be obtained; its
     *                                    failure type decides whether the send is retried
     */
    FcmAccessToken token();

    /**
     * Forget the cached token, because FCM rejected it; the next {@link #token()} fetches a new one.
     */
    void invalidate();

    /**
     * @return the Firebase project id the credentials belong to, when they name one
     *         (a service-account JSON does); the tenant's {@code project-id} wins over it
     */
    Optional<String> projectId();

    /**
     * Release resources; the provider calls it from {@code destroy()}.
     * The transport is not owned by the token provider and must not be closed.
     */
    @Override
    void close();
}
