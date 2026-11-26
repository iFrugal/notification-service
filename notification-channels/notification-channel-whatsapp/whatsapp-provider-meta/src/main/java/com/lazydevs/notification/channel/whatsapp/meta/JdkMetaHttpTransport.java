package com.lazydevs.notification.channel.whatsapp.meta;

import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.FailureTypes;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;

/**
 * The default {@link MetaHttpTransport}, built on the JDK's {@code java.net.http.HttpClient}.
 *
 * <p>{@link #shared()} is one client for the whole JVM, so every tenant's
 * provider shares its connections to the Graph API. It follows no redirects and
 * uses the JVM's default proxy selector and TLS settings; for anything else
 * supply your own {@link MetaHttpTransport}.
 *
 * <p>A failure to connect (refused, unknown host, no route, TLS handshake,
 * connect timeout) is {@code requestSent=false}; a request timeout, any other
 * I/O failure and an interrupt are {@code requestSent=true}.
 *
 * @since 1.3.0
 */
public final class JdkMetaHttpTransport implements MetaHttpTransport {

    /** Connect timeout of the shared client; the per-request timeout covers the whole exchange. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final class Shared {
        private static final JdkMetaHttpTransport INSTANCE = new JdkMetaHttpTransport(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    private final HttpClient client;

    /**
     * A transport over your own JDK client, for example one with a proxy selector.
     *
     * @param client the client; it is not closed by this transport
     */
    public JdkMetaHttpTransport(HttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * @return the JVM-wide transport over one shared client, created on first use
     */
    public static JdkMetaHttpTransport shared() {
        return Shared.INSTANCE;
    }

    @Override
    public MetaHttpResponse execute(MetaHttpRequest request) throws MetaTransportException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(request.timeout())
                .method(request.method(), HttpRequest.BodyPublishers.ofByteArray(request.body()));
        request.headers().forEach(builder::header);
        try {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new MetaHttpResponse(response.statusCode(), response.headers().map(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // The request may already be on the wire.
            throw MetaTransportException.afterSend("interrupted while waiting for " + request.uri(), e);
        } catch (IOException e) {
            throw map(request, e);
        }
    }

    static MetaTransportException map(MetaHttpRequest request, IOException e) {
        String what = e.getClass().getSimpleName() + " calling " + request.uri();
        if (FailureTypes.fromExceptionAfterSubmit(e) == FailureType.TRANSIENT) {
            // Connect-phase failure, including HttpConnectTimeoutException: nothing was sent.
            return MetaTransportException.notSent(what, e);
        }
        if (e instanceof HttpTimeoutException) {
            return MetaTransportException.afterSend(what + " (no response within " + request.timeout() + ")", e);
        }
        return MetaTransportException.afterSend(what, e);
    }

    @Override
    public String toString() {
        return "JdkMetaHttpTransport[" + client.version() + "]";
    }
}
