package com.lazydevs.notification.core.idempotency;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SmsRecipient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    @Test
    void isA64CharacterHexDigest_andStable() {
        String fp = RequestFingerprint.of(request(Map.of("name", "Ada")));

        assertThat(fp).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(RequestFingerprint.of(request(Map.of("name", "Ada")))).isEqualTo(fp);
    }

    @Test
    void mapKeyOrderDoesNotMatter_atAnyDepth() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("x", 1);
        a.put("nested", new LinkedHashMap<>(Map.of("b", 2, "a", 1)));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("nested", new LinkedHashMap<>(Map.of("a", 1, "b", 2)));
        b.put("x", 1);

        assertThat(RequestFingerprint.of(request(a))).isEqualTo(RequestFingerprint.of(request(b)));
        assertThat(RequestFingerprint.canonicalJson(a)).isEqualTo("{\"nested\":{\"a\":1,\"b\":2},\"x\":1}");
    }

    @Test
    void arrayOrderMatters() {
        assertThat(RequestFingerprint.of(request(Map.of("l", List.of(1, 2)))))
                .isNotEqualTo(RequestFingerprint.of(request(Map.of("l", List.of(2, 1)))));
    }

    @Test
    void numbersKeepTheirParsedForm() {
        assertThat(RequestFingerprint.of(request(Map.of("n", 45))))
                .isNotEqualTo(RequestFingerprint.of(request(Map.of("n", 45.0))));
        assertThat(RequestFingerprint.of(request(Map.of("n", 45))))
                .isEqualTo(RequestFingerprint.of(request(Map.of("n", 45L))));
        assertThat(RequestFingerprint.canonicalJson(new BigDecimal("45.0"))).isEqualTo("45.0");
    }

    @Test
    void stringsAndKeysAreNfcNormalised() {
        String composed = "café";
        String decomposed = "café";

        assertThat(RequestFingerprint.of(request(Map.of(composed, composed))))
                .isEqualTo(RequestFingerprint.of(request(Map.of(decomposed, decomposed))));
    }

    @Test
    void nullsArePreserved() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("a", null);

        assertThat(RequestFingerprint.of(request(withNull))).isNotEqualTo(RequestFingerprint.of(request(Map.of())));
        assertThat(RequestFingerprint.canonicalJson(withNull)).isEqualTo("{\"a\":null}");
    }

    @Test
    void correlationIdMetadataRequestIdAndRecipientTrackingIdAreExcluded() {
        NotificationRequest a = request(Map.of("k", "v"));
        NotificationRequest b = request(Map.of("k", "v"));
        b.setCorrelationId("other");
        b.setMetadata(Map.of("trace", "1"));
        b.setRequestId("another-request");
        b.setRecipient(new EmailRecipient("tracking-9", "user@example.com", List.of("cc@example.com"), null, null,
                null));
        a.setRecipient(new EmailRecipient(null, "user@example.com", List.of("cc@example.com"), null, null, null));

        assertThat(RequestFingerprint.of(a)).isEqualTo(RequestFingerprint.of(b));
    }

    @Test
    void recipientsChannelTemplateAndExplicitProviderAreIncluded() {
        String base = RequestFingerprint.of(request(Map.of()));

        NotificationRequest otherTo = request(Map.of());
        otherTo.setRecipient(new EmailRecipient(null, "other@example.com", null, null, null, null));
        NotificationRequest ccOrder = request(Map.of());
        ccOrder.setRecipient(new EmailRecipient(null, "user@example.com", List.of("b@x.io", "a@x.io"), null, null,
                null));
        NotificationRequest template = request(Map.of());
        template.setTemplateId("other-template");
        NotificationRequest provider = request(Map.of());
        provider.setProvider("smtp");
        NotificationRequest sms = request(Map.of());
        sms.setChannel(Channel.SMS);
        sms.setRecipient(new SmsRecipient(null, "+15551234567"));

        assertThat(List.of(otherTo, ccOrder, template, provider, sms))
                .extracting(RequestFingerprint::of)
                .doesNotContain(base)
                .doesNotHaveDuplicates();
    }

    @Test
    void attachmentsAreFingerprintedByNameSizeAndContentHash() {
        NotificationRequest a = request(Map.of());
        a.setAttachments(List.of(new NotificationRequest.Attachment("a.pdf", "application/pdf",
                new byte[] {1, 2, 3}, null)));
        NotificationRequest sameBytes = request(Map.of());
        sameBytes.setAttachments(List.of(new NotificationRequest.Attachment("a.pdf", "application/pdf",
                new byte[] {1, 2, 3}, null)));
        NotificationRequest otherBytes = request(Map.of());
        otherBytes.setAttachments(List.of(new NotificationRequest.Attachment("a.pdf", "application/pdf",
                new byte[] {1, 2, 4}, null)));
        NotificationRequest byUrl = request(Map.of());
        byUrl.setAttachments(Arrays.asList(new NotificationRequest.Attachment("a.pdf", null, null,
                "https://files.example.com/a.pdf"), null));

        assertThat(RequestFingerprint.of(a)).isEqualTo(RequestFingerprint.of(sameBytes))
                .isNotEqualTo(RequestFingerprint.of(otherBytes))
                .isNotEqualTo(RequestFingerprint.of(byUrl));
    }

    @Test
    void nonJsonValuesAreConvertedStably() {
        record Order(String id, int qty) {
        }
        Instant at = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(RequestFingerprint.canonicalJson(new Order("o-1", 2))).isEqualTo("{\"id\":\"o-1\",\"qty\":2}");
        assertThat(RequestFingerprint.canonicalJson(at)).isEqualTo("\"2026-01-01T00:00:00Z\"");
        assertThat(RequestFingerprint.canonicalJson(new int[] {1, 2})).isEqualTo("[1,2]");
        assertThat(RequestFingerprint.canonicalJson(Channel.EMAIL)).isEqualTo("\"EMAIL\"");
        assertThat(RequestFingerprint.canonicalJson('c')).isEqualTo("\"c\"");
        assertThat(RequestFingerprint.canonicalJson(true)).isEqualTo("true");
        assertThat(RequestFingerprint.canonicalJson("q\"\\\n")).isEqualTo("\"q\\\"\\\\\\u000a\"");
    }

    @Test
    void requestWithoutRecipientOrChannelStillFingerprints() {
        NotificationRequest bare = new NotificationRequest();

        assertThat(RequestFingerprint.of(bare)).hasSize(64);
    }

    private static NotificationRequest request(Map<String, Object> templateData) {
        return NotificationRequest.builder()
                .requestId("r-1")
                .tenantId("acme")
                .notificationType("WELCOME")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .templateData(templateData)
                .build();
    }
}
