package com.lazydevs.notification.channel.whatsapp.meta;

/**
 * The HTTP client {@link MetaWhatsAppProvider} sends through.
 *
 * <p>The default is {@link JdkMetaHttpTransport}, built on {@code java.net.http}.
 * Replace it with a Spring bean of this type, for example to route through a
 * proxy or to add client metrics.
 *
 * <p>Implementations must be thread-safe: one instance serves concurrent sends.
 * They must not retry on their own; the provider classifies every failure and
 * the library's retry executor decides.
 *
 * @since 1.2.2
 */
@FunctionalInterface
public interface MetaHttpTransport {

    /**
     * Execute one request and return the response, whatever its status code.
     *
     * @param request the request
     * @return the response; a non-2xx status is a response, not an exception
     * @throws MetaTransportException when no response arrived; it says through
     *                                {@link MetaTransportException#requestSent()} whether
     *                                the request may have reached Meta
     */
    MetaHttpResponse execute(MetaHttpRequest request) throws MetaTransportException;
}
