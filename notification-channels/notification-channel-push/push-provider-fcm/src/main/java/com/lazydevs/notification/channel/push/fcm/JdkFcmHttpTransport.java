package com.lazydevs.notification.channel.push.fcm;

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
 * The default {@link FcmHttpTransport}, built on the JDK's {@code java.net.http.HttpClient}.
 *
 * <p>{@link #shared()} is one HTTP/2 client for the whole JVM, so every tenant's
 * provider multiplexes its sends over the same connections to
 * {@code fcm.googleapis.com}.
 * It follows no redirects and uses the JVM's default proxy selector and TLS
 * settings; for anything else (a proxy, client metrics, a different client
 * library) supply your own {@link FcmHttpTransport}.
 *
 * <p>Failures are mapped to {@link FcmTransportException}: a failure to connect
 * (refused, unknown host, no route, TLS handshake, connect timeout) is
 * {@code requestSent=false}; a request timeout is {@code requestSent=true,
 * timeout=true}; any other I/O failure is {@code requestSent=true}.
 *
 * @since 1.2.0
 */
public final class JdkFcmHttpTransport implements FcmHttpTransport {

    /** Connect timeout of the shared client; the per-request timeout covers the whole exchange. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final class Shared {
        private static final JdkFcmHttpTransport INSTANCE = new JdkFcmHttpTransport(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
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
    public JdkFcmHttpTransport(HttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * @return the JVM-wide transport over one shared HTTP/2 client, created on first use
     */
    public static JdkFcmHttpTransport shared() {
        return Shared.INSTANCE;
    }

    @Override
    public FcmHttpResponse execute(FcmHttpRequest request) throws FcmTransportException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(request.timeout())
                .method(request.method(), HttpRequest.BodyPublishers.ofByteArray(request.body()));
        request.headers().forEach(builder::header);
        try {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new FcmHttpResponse(response.statusCode(), response.headers().map(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // The request may already be on the wire.
            throw FcmTransportException.afterSend("interrupted while waiting for " + request.uri(), e);
        } catch (IOException e) {
            throw map(request, e);
        }
    }

    static FcmTransportException map(FcmHttpRequest request, IOException e) {
        String what = e.getClass().getSimpleName() + " calling " + request.uri();
        if (FailureTypes.fromExceptionAfterSubmit(e) == FailureType.TRANSIENT) {
            // Connect-phase failure, including HttpConnectTimeoutException: nothing was sent.
            return FcmTransportException.notSent(what, e);
        }
        if (e instanceof HttpTimeoutException) {
            return FcmTransportException.timedOut(what + " (no response within " + request.timeout() + ")", e);
        }
        return FcmTransportException.afterSend(what, e);
    }

    @Override
    public String toString() {
        return "JdkFcmHttpTransport[" + client.version() + "]";
    }
}
