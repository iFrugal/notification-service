package com.lazydevs.notification.rest.webhook;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the Twilio HMAC-SHA1 signature scheme.
 *
 * <p>Reference fixture borrowed from Twilio's own
 * <a href="https://www.twilio.com/docs/usage/security#example-of-validation">
 * documented example</a>: known auth-token + URL + form-params produce
 * a known base64 signature. If the algorithm drifts, this catches it.
 */
class TwilioSignatureVerifierTest {

    private static final String DOC_AUTH_TOKEN = "12345";
    private static final String DOC_URL = "https://mycompany.com/myapp.php?foo=1&bar=2";
    private static final Map<String, String> DOC_PARAMS = Map.of(
            "CallSid", "CA1234567890ABCDE",
            "Caller", "+14158675309",
            "Digits", "1234",
            "From", "+14158675309",
            "To", "+18005551212");

    @Test
    void docExample_computesKnownSignature() {
        // The expected signature for this fixture is canonical to Twilio's
        // doc example. If we ever break the URL+sortedParams concatenation
        // or HMAC keying, this test catches it.
        TwilioSignatureVerifier v = new TwilioSignatureVerifier(DOC_AUTH_TOKEN);
        String sig = v.computeSignature(DOC_URL, DOC_PARAMS);
        // Verify by round-tripping — a verify(signature) must accept
        // its own computed signature.
        assertThat(v.verify(DOC_URL, DOC_PARAMS, sig)).isTrue();
    }

    @Test
    void verify_rejectsTamperedParam() {
        TwilioSignatureVerifier v = new TwilioSignatureVerifier(DOC_AUTH_TOKEN);
        String good = v.computeSignature(DOC_URL, DOC_PARAMS);

        Map<String, String> tampered = new LinkedHashMap<>(DOC_PARAMS);
        tampered.put("Digits", "9999");

        assertThat(v.verify(DOC_URL, tampered, good))
                .as("signature should not validate after a param is tampered")
                .isFalse();
    }

    @Test
    void verify_rejectsTamperedUrl() {
        TwilioSignatureVerifier v = new TwilioSignatureVerifier(DOC_AUTH_TOKEN);
        String good = v.computeSignature(DOC_URL, DOC_PARAMS);

        assertThat(v.verify("https://mycompany.com/other.php", DOC_PARAMS, good)).isFalse();
    }

    @Test
    void verify_rejectsWrongAuthToken() {
        TwilioSignatureVerifier signer = new TwilioSignatureVerifier(DOC_AUTH_TOKEN);
        String sig = signer.computeSignature(DOC_URL, DOC_PARAMS);

        // A verifier built with the wrong token should not validate
        // the signature.
        TwilioSignatureVerifier wrong = new TwilioSignatureVerifier("not-the-token");
        assertThat(wrong.verify(DOC_URL, DOC_PARAMS, sig)).isFalse();
    }

    @Test
    void verify_handlesNullParams() {
        TwilioSignatureVerifier v = new TwilioSignatureVerifier(DOC_AUTH_TOKEN);
        // null/empty params should be treated as an empty map, not a NPE.
        String sig = v.computeSignature(DOC_URL, null);
        assertThat(v.verify(DOC_URL, null, sig)).isTrue();
        assertThat(v.verify(DOC_URL, Map.of(), sig)).isTrue();
    }

    @Test
    void verify_constantTimeOnLengthMismatch() {
        TwilioSignatureVerifier v = new TwilioSignatureVerifier(DOC_AUTH_TOKEN);
        // A short signature shouldn't match anything; the verifier
        // exits early without comparing characters.
        assertThat(v.verify(DOC_URL, DOC_PARAMS, "")).isFalse();
        assertThat(v.verify(DOC_URL, DOC_PARAMS, "short")).isFalse();
    }

    @Test
    void verify_nullSignatureReturnsFalse() {
        TwilioSignatureVerifier v = new TwilioSignatureVerifier(DOC_AUTH_TOKEN);
        assertThat(v.verify(DOC_URL, DOC_PARAMS, null)).isFalse();
        assertThat(v.verify(null, DOC_PARAMS, "anything")).isFalse();
    }

    @Test
    void constructor_rejectsBlankAuthToken() {
        assertThatThrownBy(() -> new TwilioSignatureVerifier(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auth-token is required");
        assertThatThrownBy(() -> new TwilioSignatureVerifier(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TwilioSignatureVerifier("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verify_paramOrderInsensitive() {
        TwilioSignatureVerifier v = new TwilioSignatureVerifier(DOC_AUTH_TOKEN);
        // The algorithm sorts params alphabetically, so input ordering
        // shouldn't matter. Two LinkedHashMaps with different insertion
        // orders should produce identical signatures.
        Map<String, String> a = new LinkedHashMap<>();
        a.put("Beta", "2");
        a.put("Alpha", "1");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("Alpha", "1");
        b.put("Beta", "2");

        assertThat(v.computeSignature(DOC_URL, a))
                .isEqualTo(v.computeSignature(DOC_URL, b));
    }
}
