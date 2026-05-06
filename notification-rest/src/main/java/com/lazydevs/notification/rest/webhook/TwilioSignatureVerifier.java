package com.lazydevs.notification.rest.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * Verifies Twilio's {@code X-Twilio-Signature} header per the algorithm
 * documented at
 * <a href="https://www.twilio.com/docs/usage/webhooks/webhooks-security">
 * Twilio webhook security</a>.
 *
 * <p>Algorithm (extracted here so it's testable in isolation):
 * <ol>
 *   <li>Take the full request URL (scheme + host + path + query) as
 *       Twilio saw it. Behind a reverse proxy this means the URL the
 *       proxy registered, not the internal one.</li>
 *   <li>Sort the form-encoded POST parameters alphabetically by key.</li>
 *   <li>Append each {@code key + value} (no separators) to the URL
 *       string.</li>
 *   <li>HMAC-SHA1 the resulting string with the account auth token as
 *       the key.</li>
 *   <li>Base64-encode the digest and compare to {@code X-Twilio-Signature}.</li>
 * </ol>
 *
 * <p>Constant-time comparison is used to avoid timing-side-channel
 * leaks of the expected signature.
 */
public final class TwilioSignatureVerifier {

    private static final String HMAC_ALGO = "HmacSHA1";

    private final String authToken;

    public TwilioSignatureVerifier(String authToken) {
        if (authToken == null || authToken.isBlank()) {
            throw new IllegalArgumentException(
                    "Twilio auth-token is required for signature verification "
                            + "(set notification.webhooks.twilio.auth-token or disable verification)");
        }
        this.authToken = authToken;
    }

    /**
     * @param requestUrl    full URL Twilio used to call the webhook
     * @param params        form-encoded POST parameters from the body
     * @param signatureB64  the {@code X-Twilio-Signature} header value
     * @return {@code true} if the recomputed HMAC matches the supplied
     *         signature in constant time
     */
    public boolean verify(String requestUrl, Map<String, String> params, String signatureB64) {
        if (requestUrl == null || signatureB64 == null) {
            return false;
        }
        String expected = computeSignature(requestUrl, params);
        return constantTimeEquals(expected, signatureB64);
    }

    String computeSignature(String requestUrl, Map<String, String> params) {
        // Sort params alphabetically by key. Twilio's algorithm wants
        // a stable order; using TreeMap is the simplest stable choice.
        StringBuilder sb = new StringBuilder(requestUrl);
        Map<String, String> sorted = (params == null) ? Map.of() : new TreeMap<>(params);
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            sb.append(e.getKey()).append(e.getValue() == null ? "" : e.getValue());
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] raw = mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (java.security.GeneralSecurityException e) {
            // HMAC-SHA1 is guaranteed available on every JDK; if this
            // throws, something is very wrong with the runtime.
            throw new IllegalStateException("HMAC-SHA1 unavailable on this JDK", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
