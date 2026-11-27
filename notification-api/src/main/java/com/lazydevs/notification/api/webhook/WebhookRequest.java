package com.lazydevs.notification.api.webhook;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One provider callback, as received over HTTP (DD-36).
 *
 * <p>The body is kept as raw bytes because provider signatures are computed
 * over the exact bytes sent; re-serialising a parsed body would break them.
 * {@link #toString()} prints neither header values nor the body.
 *
 * @param method          the HTTP method, upper case, for example {@code POST}
 * @param subPath         the path below the handler id, without leading or
 *                        trailing {@code /}; empty for the handler's root, for
 *                        example {@code status} for {@code /webhooks/twilio/status}
 * @param headers         request headers; names are case-insensitive
 * @param queryParameters decoded query string parameters, in order of appearance
 * @param body            the raw request body, never {@code null}
 * @param requestUrl      the full URL the provider called, including the query
 *                        string, as needed by schemes that sign the URL; may be {@code null}
 * @since 1.2.2
 */
public record WebhookRequest(
        String method,
        String subPath,
        Map<String, List<String>> headers,
        Map<String, List<String>> queryParameters,
        byte[] body,
        String requestUrl) {

    /** HTTP method {@code GET}. */
    public static final String GET = "GET";

    /** HTTP method {@code POST}. */
    public static final String POST = "POST";

    /**
     * Normalises and copies the arguments.
     */
    public WebhookRequest {
        Objects.requireNonNull(method, "method");
        method = method.toUpperCase(Locale.ROOT);
        subPath = trimSlashes(subPath);
        TreeMap<String, List<String>> headerCopy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        copyInto(headers, headerCopy);
        headers = Collections.unmodifiableMap(headerCopy);
        Map<String, List<String>> queryCopy = new LinkedHashMap<>();
        copyInto(queryParameters, queryCopy);
        queryParameters = Collections.unmodifiableMap(queryCopy);
        body = body == null ? new byte[0] : body.clone();
    }

    /** @return a copy of the raw body */
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
        return first(headers.get(name));
    }

    /**
     * @param name query parameter name, case-sensitive
     * @return the first value of the parameter, or {@code null}
     */
    public String queryParameter(String name) {
        return first(queryParameters.get(name));
    }

    /**
     * @param httpMethod an HTTP method, case-insensitive
     * @return whether this request used it
     */
    public boolean isMethod(String httpMethod) {
        return method.equalsIgnoreCase(httpMethod);
    }

    private static String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static void copyInto(Map<String, List<String>> source, Map<String, List<String>> target) {
        if (source == null) {
            return;
        }
        source.forEach((name, values) -> {
            if (name != null) {
                target.put(name, values == null ? List.of() : List.copyOf(values));
            }
        });
    }

    private static String trimSlashes(String path) {
        if (path == null) {
            return "";
        }
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') {
            start++;
        }
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(start, end);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WebhookRequest other
                && method.equals(other.method)
                && subPath.equals(other.subPath)
                && headers.equals(other.headers)
                && queryParameters.equals(other.queryParameters)
                && Arrays.equals(body, other.body)
                && Objects.equals(requestUrl, other.requestUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, subPath, headers, queryParameters, requestUrl) * 31 + Arrays.hashCode(body);
    }

    /** Never prints header values, query values or the body: they may carry secrets or personal data. */
    @Override
    public String toString() {
        return "WebhookRequest[" + method + " " + subPath + ", headers=" + headers.keySet()
                + ", query=" + queryParameters.keySet() + ", body=byte[" + body.length + "]]";
    }
}
