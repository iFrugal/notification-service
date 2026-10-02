package com.lazydevs.notification.channel.push.fcm.googleauth;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.util.StreamingContent;
import com.lazydevs.notification.channel.push.fcm.FcmHttpRequest;
import com.lazydevs.notification.channel.push.fcm.FcmHttpResponse;
import com.lazydevs.notification.channel.push.fcm.FcmHttpTransport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * A google-http-client {@link HttpTransport} over an {@link FcmHttpTransport}, so
 * that every HTTP call the Google auth library makes (token exchanges, service
 * account impersonation, metadata server, subject token URLs) goes through the
 * application's FCM transport rather than the library's own {@code NetHttpTransport}.
 *
 * <ul>
 *   <li>Request headers are passed on, except the framing headers the transport sets
 *       itself ({@code Content-Length}, {@code Host}, {@code Connection}, {@code Expect},
 *       {@code Upgrade}, {@code Transfer-Encoding}); a header added twice is joined with
 *       {@code ", "}. The content type and content encoding become headers.</li>
 *   <li>The request timeout is the library's connect plus read timeout, at most the
 *       {@code maxTimeout} given here (the tenant's {@code timeout}).</li>
 *   <li>The response status, headers and body are handed back unchanged; the library
 *       decompresses a gzip body itself, from the {@code Content-Encoding} header.</li>
 *   <li>An {@link com.lazydevs.notification.channel.push.fcm.FcmTransportException} (no
 *       response) is thrown as is: it is an {@link IOException}.</li>
 * </ul>
 *
 * @since 1.2.0
 */
public final class FcmHttpTransportAdapter extends HttpTransport {

    /** Framing headers the underlying transport computes; {@code java.net.http} rejects most of them. */
    private static final Set<String> FRAMING_HEADERS = Set.of(
            "content-length", "host", "connection", "expect", "upgrade", "transfer-encoding");

    private final FcmHttpTransport transport;
    private final Duration maxTimeout;

    /**
     * @param transport  the transport every request goes through
     * @param maxTimeout the longest timeout of one request
     */
    public FcmHttpTransportAdapter(FcmHttpTransport transport, Duration maxTimeout) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.maxTimeout = Objects.requireNonNull(maxTimeout, "maxTimeout");
        if (maxTimeout.isZero() || maxTimeout.isNegative()) {
            throw new IllegalArgumentException("maxTimeout must be positive: " + maxTimeout);
        }
    }

    /** @return the transport every request goes through */
    public FcmHttpTransport transport() {
        return transport;
    }

    /** Any method: the FCM transport takes the method as a string. */
    @Override
    public boolean supportsMethod(String method) {
        return true;
    }

    @Override
    protected LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
        try {
            return new Request(method, new URI(url));
        } catch (URISyntaxException e) {
            throw new IOException("not a valid URI: " + e.getInput(), e);
        }
    }

    @Override
    public String toString() {
        return "FcmHttpTransportAdapter[" + transport + "]";
    }

    private final class Request extends LowLevelHttpRequest {

        private final String method;
        private final URI uri;
        private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private Duration timeout = maxTimeout;

        private Request(String method, URI uri) {
            this.method = method;
            this.uri = uri;
        }

        @Override
        public void addHeader(String name, String value) {
            if (name == null || value == null || FRAMING_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            headers.merge(name, value, (first, second) -> first + ", " + second);
        }

        /** Connect plus read timeout, capped; {@code 0} (no limit) means the cap. */
        @Override
        public void setTimeout(int connectTimeoutMillis, int readTimeoutMillis) {
            if (connectTimeoutMillis <= 0 || readTimeoutMillis <= 0) {
                timeout = maxTimeout;
                return;
            }
            Duration requested = Duration.ofMillis((long) connectTimeoutMillis + readTimeoutMillis);
            timeout = requested.compareTo(maxTimeout) < 0 ? requested : maxTimeout;
        }

        @Override
        public LowLevelHttpResponse execute() throws IOException {
            byte[] body = new byte[0];
            StreamingContent content = getStreamingContent();
            if (content != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                content.writeTo(out);
                body = out.toByteArray();
            }
            if (getContentType() != null) {
                headers.put("Content-Type", getContentType());
            }
            if (getContentEncoding() != null) {
                headers.put("Content-Encoding", getContentEncoding());
            }
            FcmHttpResponse response = transport.execute(new FcmHttpRequest(method, uri, headers, body, timeout));
            return new Response(response);
        }
    }

    private static final class Response extends LowLevelHttpResponse {

        private final FcmHttpResponse response;
        private final byte[] body;
        private final List<String> names = new ArrayList<>();
        private final List<String> values = new ArrayList<>();

        private Response(FcmHttpResponse response) {
            this.response = response;
            this.body = response.body();
            response.headers().forEach((name, list) -> list.forEach(value -> {
                names.add(name);
                values.add(value);
            }));
        }

        /** {@code null} for an empty body, which the library reads as no content. */
        @Override
        public InputStream getContent() {
            return body.length == 0 ? null : new ByteArrayInputStream(body);
        }

        @Override
        public String getContentEncoding() {
            return response.header("Content-Encoding");
        }

        @Override
        public long getContentLength() {
            return body.length;
        }

        @Override
        public String getContentType() {
            return response.header("Content-Type");
        }

        @Override
        public String getStatusLine() {
            return "HTTP/1.1 " + response.statusCode();
        }

        @Override
        public int getStatusCode() {
            return response.statusCode();
        }

        /** The FCM transport does not report one. */
        @Override
        public String getReasonPhrase() {
            return null;
        }

        @Override
        public int getHeaderCount() {
            return names.size();
        }

        @Override
        public String getHeaderName(int index) {
            return names.get(index);
        }

        @Override
        public String getHeaderValue(int index) {
            return values.get(index);
        }
    }
}
