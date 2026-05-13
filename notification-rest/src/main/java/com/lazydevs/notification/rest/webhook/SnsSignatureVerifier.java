package com.lazydevs.notification.rest.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Verifies SNS message signatures per the algorithm documented at
 * <a href="https://docs.aws.amazon.com/sns/latest/dg/sns-verify-signature-of-message.html">
 * SNS message signature verification</a>.
 *
 * <p>Implemented with stdlib only ({@link Signature} +
 * {@link CertificateFactory}) so we don't pull a hard runtime dep on
 * {@code aws-java-sdk-sns} into deployments that only use Twilio. The
 * signing certificate is fetched via stdlib {@link HttpClient} and
 * cached per URL with Caffeine — a single SES-backed deployment can
 * see thousands of callbacks per minute, all signed by the same cert.
 *
 * <p>{@code SignatureVersion} {@code "1"} (SHA1withRSA) and {@code "2"}
 * (SHA256withRSA) are both supported. {@code "1"} is the historical
 * default; AWS rolled {@code "2"} out in 2022 and recommends migrating.
 */
@Slf4j
public final class SnsSignatureVerifier {

    /**
     * Permitted hostnames for {@code SigningCertURL}. Per the SNS docs
     * the cert is served from {@code sns.<region>.amazonaws.com} or
     * the legacy {@code sns.amazonaws.com}; we pin the regex so an
     * attacker can't substitute their own cert URL.
     */
    private static final Pattern CERT_URL_HOST = Pattern.compile(
            "^sns(\\.[a-z0-9-]+)?\\.amazonaws\\.com$");

    private final HttpClient http;
    private final Cache<String, PublicKey> certCache;

    public SnsSignatureVerifier() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        // 1-hour TTL is enough that a busy callback stream pays for the
        // fetch once; cert rotation on AWS's side happens on a much
        // longer cadence than that.
        this.certCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(1))
                .maximumSize(16)
                .build();
    }

    /**
     * Verify that {@code root.Signature} matches the canonical
     * string-to-sign for {@code root}, and that the signing cert is
     * served from an AWS-owned host.
     *
     * @return {@code true} on full verification success
     */
    public boolean verify(JsonNode root) {
        if (root == null) {
            return false;
        }
        String type = text(root, "Type");
        String signingCertUrl = text(root, "SigningCertURL");
        String signatureB64 = text(root, "Signature");
        String signatureVersion = text(root, "SignatureVersion");
        if (type == null || signingCertUrl == null || signatureB64 == null) {
            return false;
        }
        if (!isAllowedCertUrl(signingCertUrl)) {
            log.warn("SNS signing-cert URL host not allowed: {}", signingCertUrl);
            return false;
        }
        String stringToSign = buildStringToSign(type, root);
        if (stringToSign == null) {
            return false;
        }
        try {
            PublicKey pk = certCache.get(signingCertUrl, this::fetchPublicKey);
            if (pk == null) {
                return false;
            }
            String algo = "2".equals(signatureVersion) ? "SHA256withRSA" : "SHA1withRSA";
            Signature verifier = Signature.getInstance(algo);
            verifier.initVerify(pk);
            verifier.update(stringToSign.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signatureB64));
        } catch (java.security.GeneralSecurityException | RuntimeException e) {
            log.warn("SNS signature verification failed: {}", e.toString());
            return false;
        }
    }

    static boolean isAllowedCertUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost();
            return host != null && CERT_URL_HOST.matcher(host.toLowerCase()).matches();
        } catch (IllegalArgumentException | NullPointerException _) {
            // URI.create chokes on whitespace and a few other invalid
            // forms with NPE; treat any parsing failure as "not
            // allowed" — we never validate untrustworthy URLs against
            // a strict spec, the regex is the trust boundary.
            return false;
        }
    }

    /**
     * Build the canonical string-to-sign per the SNS docs. Field order
     * is alphabetical by key, with each line {@code key\nvalue\n}.
     * Different message types include different fields:
     * <ul>
     *   <li>{@code Notification}: Message, MessageId, Subject (if
     *       present), Timestamp, TopicArn, Type</li>
     *   <li>{@code SubscriptionConfirmation},
     *       {@code UnsubscribeConfirmation}: Message, MessageId,
     *       SubscribeURL, Timestamp, Token, TopicArn, Type</li>
     * </ul>
     */
    static String buildStringToSign(String type, JsonNode root) {
        StringBuilder sb = new StringBuilder();
        if ("Notification".equals(type)) {
            appendField(sb, "Message", root);
            appendField(sb, "MessageId", root);
            if (root.hasNonNull("Subject")) {
                appendField(sb, "Subject", root);
            }
            appendField(sb, "Timestamp", root);
            appendField(sb, "TopicArn", root);
            appendField(sb, "Type", root);
        } else if ("SubscriptionConfirmation".equals(type)
                || "UnsubscribeConfirmation".equals(type)) {
            appendField(sb, "Message", root);
            appendField(sb, "MessageId", root);
            appendField(sb, "SubscribeURL", root);
            appendField(sb, "Timestamp", root);
            appendField(sb, "Token", root);
            appendField(sb, "TopicArn", root);
            appendField(sb, "Type", root);
        } else {
            return null;
        }
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String name, JsonNode root) {
        sb.append(name).append('\n');
        sb.append(text(root, name)).append('\n');
    }

    private static String text(JsonNode root, String name) {
        JsonNode n = root.get(name);
        return n == null || n.isNull() ? null : n.asText();
    }

    private PublicKey fetchPublicKey(String url) {
        try {
            HttpResponse<InputStream> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .GET()
                            .timeout(Duration.ofSeconds(5))
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                log.warn("SNS signing-cert fetch returned HTTP {} from {}", resp.statusCode(), url);
                return null;
            }
            try (InputStream is = resp.body()) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                X509Certificate cert = (X509Certificate) cf.generateCertificate(is);
                return cert.getPublicKey();
            }
        } catch (java.io.IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("SNS signing-cert fetch from {} failed: {}", url, e.toString());
            return null;
        } catch (java.security.cert.CertificateException e) {
            log.warn("SNS signing-cert from {} not a valid X.509: {}", url, e.toString());
            return null;
        }
    }
}
