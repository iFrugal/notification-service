package com.lazydevs.notification.channel.push.fcm;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One HTTP request made by {@link FcmPushProvider} through an {@link FcmHttpTransport}.
 *
 * <p>The {@code Authorization} header of a send call carries a bearer token, and
 * the body of a token call carries a signed assertion: never log either.
 * {@link #toString()} shows only the method, the URI, the header names and the
 * body length.
 *
 * @param method  {@code POST} for every call the provider makes
 * @param uri     the absolute URI
 * @param headers request headers; names are case-insensitive
 * @param body    the request body, never {@code null}
 * @param timeout how long to wait for the complete response
 * @since 1.2.0
 */
public record FcmHttpRequest(String method, URI uri, Map<String, String> headers, byte[] body, Duration timeout) {

    /**
     * Validates and copies the arguments.
     */
    public FcmHttpRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(timeout, "timeout");
        TreeMap<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            copy.putAll(headers);
        }
        headers = Collections.unmodifiableMap(copy);
        body = body == null ? new byte[0] : body.clone();
    }

    /** @return a copy of the body */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /**
     * @param name header name, case-insensitive
     * @return the header value, or {@code null}
     */
    public String header(String name) {
        return headers.get(name);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FcmHttpRequest other
                && method.equals(other.method)
                && uri.equals(other.uri)
                && headers.equals(other.headers)
                && Arrays.equals(body, other.body)
                && timeout.equals(other.timeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, uri, headers, timeout) * 31 + Arrays.hashCode(body);
    }

    /** Never prints header values or the body: they carry credentials. */
    @Override
    public String toString() {
        return "FcmHttpRequest[" + method + " " + uri + ", headers=" + headers.keySet()
                + ", body=byte[" + body.length + "], timeout=" + timeout + "]";
    }
}
