package com.lazydevs.notification.channel.push.fcm;

/**
 * The HTTP client {@link FcmPushProvider} sends through: both the FCM HTTP v1
 * send call and the OAuth 2.0 token exchange.
 *
 * <p>The default is {@link JdkFcmHttpTransport}, built on {@code java.net.http}.
 * Replace it with a Spring bean of this type (used by the auto-configured
 * {@code fcmPushProvider}), with a {@code META-INF/services} entry (used by the
 * no-arg constructor), or per tenant with the {@code http-transport} key, which
 * names a bean; for example to route through a proxy or to add metrics.
 *
 * <p>Implementations must be thread-safe: one instance serves concurrent sends.
 * They must not retry on their own; the provider classifies every failure and
 * the library's retry executor decides.
 *
 * @since 1.2.0
 */
@FunctionalInterface
public interface FcmHttpTransport {

    /**
     * Execute one request and return the response, whatever its status code.
     *
     * @param request the request
     * @return the response; a non-2xx status is a response, not an exception
     * @throws FcmTransportException when no response arrived; it says through
     *                               {@link FcmTransportException#requestSent()} whether
     *                               the request may have reached the server
     */
    FcmHttpResponse execute(FcmHttpRequest request) throws FcmTransportException;
}
