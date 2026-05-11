package com.lazydevs.notification.rest.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the parts of {@link SnsSignatureVerifier} that don't
 * require network — the cert URL host check and the canonical
 * string-to-sign construction. Full end-to-end signature verification
 * is exercised in {@link WebhookControllerTest} via a mocked
 * {@code SnsSignatureVerifier} so we don't have to ship a test-only
 * X.509 keypair.
 */
class SnsSignatureVerifierTest {

    @Test
    void allowedCertUrl_acceptsAwsHosts() {
        assertThat(SnsSignatureVerifier.isAllowedCertUrl(
                "https://sns.us-east-1.amazonaws.com/SimpleNotificationService-cert.pem")).isTrue();
        assertThat(SnsSignatureVerifier.isAllowedCertUrl(
                "https://sns.eu-west-2.amazonaws.com/abc.pem")).isTrue();
        assertThat(SnsSignatureVerifier.isAllowedCertUrl(
                "https://sns.amazonaws.com/legacy.pem")).isTrue();
    }

    @Test
    void allowedCertUrl_rejectsNonAwsHosts() {
        // Bypass attempts: subdomain trick, look-alike domain, http,
        // unknown TLD. Each must reject — otherwise an attacker could
        // serve their own cert and make us validate forged messages
        // against it.
        assertThat(SnsSignatureVerifier.isAllowedCertUrl(
                "https://attacker.com/SimpleNotificationService-cert.pem")).isFalse();
        assertThat(SnsSignatureVerifier.isAllowedCertUrl(
                "https://sns.us-east-1.amazonaws.com.attacker.com/cert.pem")).isFalse();
        assertThat(SnsSignatureVerifier.isAllowedCertUrl(
                "https://attacker-sns.us-east-1.amazonaws.com/cert.pem")).isFalse();
        assertThat(SnsSignatureVerifier.isAllowedCertUrl(
                "http://sns.us-east-1.amazonaws.com/cert.pem"))
                .as("plain HTTP not allowed — the cert URL is the trust anchor")
                .isFalse();
        assertThat(SnsSignatureVerifier.isAllowedCertUrl(
                "https://sns.amazonaws.cn/cert.pem"))
                .as("amazonaws.cn isn't in the allow-list — narrow scope")
                .isFalse();
        assertThat(SnsSignatureVerifier.isAllowedCertUrl(null)).isFalse();
        assertThat(SnsSignatureVerifier.isAllowedCertUrl("not a url")).isFalse();
    }

    @Test
    void buildStringToSign_notification() throws Exception {
        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree("""
                {
                  "Type": "Notification",
                  "MessageId": "abc-123",
                  "TopicArn": "arn:aws:sns:us-east-1:000:my-topic",
                  "Subject": "Delivery",
                  "Message": "{\\"some\\":\\"json\\"}",
                  "Timestamp": "2026-04-30T12:00:00.000Z",
                  "Signature": "ignored",
                  "SigningCertURL": "ignored",
                  "SignatureVersion": "1"
                }
                """);

        String sts = SnsSignatureVerifier.buildStringToSign("Notification", root);

        // Per AWS docs the field order for Notification is:
        //   Message, MessageId, Subject (if present), Timestamp, TopicArn, Type
        assertThat(sts).isEqualTo(
                "Message\n{\"some\":\"json\"}\n"
                        + "MessageId\nabc-123\n"
                        + "Subject\nDelivery\n"
                        + "Timestamp\n2026-04-30T12:00:00.000Z\n"
                        + "TopicArn\narn:aws:sns:us-east-1:000:my-topic\n"
                        + "Type\nNotification\n");
    }

    @Test
    void buildStringToSign_notificationOmitsSubjectWhenAbsent() throws Exception {
        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree("""
                {
                  "Type": "Notification",
                  "MessageId": "abc-123",
                  "TopicArn": "arn:aws:sns:us-east-1:000:my-topic",
                  "Message": "hello",
                  "Timestamp": "2026-04-30T12:00:00.000Z"
                }
                """);

        String sts = SnsSignatureVerifier.buildStringToSign("Notification", root);

        // Subject is conditionally omitted — verify there's no "Subject\n"
        // line when the field is absent.
        assertThat(sts).doesNotContain("Subject\n");
        assertThat(sts).startsWith("Message\nhello\n");
    }

    @Test
    void buildStringToSign_subscriptionConfirmation() throws Exception {
        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree("""
                {
                  "Type": "SubscriptionConfirmation",
                  "MessageId": "sub-1",
                  "TopicArn": "arn:aws:sns:us-east-1:000:my-topic",
                  "Message": "You have chosen to subscribe...",
                  "SubscribeURL": "https://sns.us-east-1.amazonaws.com/?Action=ConfirmSubscription&...",
                  "Timestamp": "2026-04-30T12:00:00.000Z",
                  "Token": "tok-1"
                }
                """);

        String sts = SnsSignatureVerifier.buildStringToSign("SubscriptionConfirmation", root);

        // Different field set + order than Notification — Token and
        // SubscribeURL replace Subject.
        assertThat(sts).contains("SubscribeURL\nhttps://sns");
        assertThat(sts).contains("Token\ntok-1");
        assertThat(sts).endsWith("Type\nSubscriptionConfirmation\n");
    }

    @Test
    void buildStringToSign_unknownTypeReturnsNull() throws Exception {
        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree("{ \"Type\": \"WeirdEnvelope\" }");
        // The whole point of the type check is to refuse to sign-check
        // envelopes whose canonical form we don't know.
        assertThat(SnsSignatureVerifier.buildStringToSign("WeirdEnvelope", root)).isNull();
    }
}
