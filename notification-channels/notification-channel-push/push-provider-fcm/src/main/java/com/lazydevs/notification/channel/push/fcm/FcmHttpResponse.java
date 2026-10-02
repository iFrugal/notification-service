package com.lazydevs.notification.channel.push.fcm;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The response an {@link FcmHttpTransport} received.
 *
 * @param statusCode the HTTP status code
 * @param headers    response headers; names are case-insensitive
 * @param body       the response body, never {@code null}
 * @since 1.2.0
 */
public record FcmHttpResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {

    /**
     * Copies the arguments; header names become case-insensitive.
     */
    public FcmHttpResponse {
        TreeMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            headers.forEach((name, values) -> {
                if (name != null) {
                    copy.put(name, values == null ? List.of() : List.copyOf(values));
                }
            });
        }
        headers = Collections.unmodifiableMap(copy);
        body = body == null ? new byte[0] : body.clone();
    }

    /** @return a copy of the body */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /** @return the body decoded as UTF-8 */
    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * @param name header name, case-insensitive
     * @return the first value of the header, or {@code null}
     */
    public String header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    /** @return whether the status code is 2xx */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FcmHttpResponse other
                && statusCode == other.statusCode
                && headers.equals(other.headers)
                && Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(statusCode, headers) * 31 + Arrays.hashCode(body);
    }

    @Override
    public String toString() {
        return "FcmHttpResponse[status=" + statusCode + ", headers=" + headers.keySet()
                + ", body=byte[" + body.length + "]]";
    }
}
