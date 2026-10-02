package com.lazydevs.notification.api.util;

import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.PushRecipient;
import com.lazydevs.notification.api.model.SmsRecipient;
import com.lazydevs.notification.api.model.WhatsAppRecipient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskingTest {

    // ---------- maskEmail ----------

    @ParameterizedTest
    @CsvSource({
            "john@example.com,         j***@example.com",
            "j@example.com,            j***@example.com",
            "John.Doe+tag@Example.COM, J***@Example.COM",
            "'  jane@example.com  ',   j***@example.com",
            "jörg@bücher.de,           j***@bücher.de",
            "élodie@exemple.fr,        é***@exemple.fr",
            "用户@例子.广告,             用***@例子.广告",
            "a\"b@c\"@example.com,      a***@example.com",
            "@example.com,             ***@example.com",
            "john@,                    j***@",
    })
    void maskEmail_keepsFirstCharacterAndDomain(String input, String expected) {
        assertThat(PiiMasking.maskEmail(input)).isEqualTo(expected);
    }

    @Test
    void maskEmail_doesNotSplitSurrogatePairs() {
        // U+1D49C MATHEMATICAL SCRIPT CAPITAL A is two UTF-16 chars.
        String local = new String(Character.toChars(0x1D49C)) + "bc";
        assertThat(PiiMasking.maskEmail(local + "@example.com"))
                .isEqualTo(new String(Character.toChars(0x1D49C)) + "***@example.com");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not-an-email", "john.example.com"})
    void maskEmail_blankOrWithoutAt_isFullyMasked(String input) {
        assertThat(PiiMasking.maskEmail(input)).isEqualTo("***");
    }

    // ---------- maskPhone ----------

    @ParameterizedTest
    @CsvSource({
            "+15551234590,         +1***90",
            "+447700900123,        +4***23",
            "15551234590,          1***90",
            "'+1 (555) 123-4590',  +1***90",
            "' +919876543210 ',    +9***10",
            "123456,               1***56",
    })
    void maskPhone_keepsPlusFirstAndLastTwoDigits(String input, String expected) {
        assertThat(PiiMasking.maskPhone(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "+12345", "abc", "+", "phone"})
    void maskPhone_blankShortOrMalformed_isFullyMasked(String input) {
        assertThat(PiiMasking.maskPhone(input)).isEqualTo("***");
    }

    // ---------- mask(Recipient) ----------

    @Test
    void mask_email_masksToAndCountsCcBcc() {
        EmailRecipient recipient = new EmailRecipient("id-1", "john@example.com",
                List.of("a@example.com", "b@example.com"), null, "reply@example.com", "Secret subject");

        String summary = PiiMasking.mask(recipient);

        assertThat(summary).isEqualTo("to=j***@example.com cc=2 bcc=0");
        assertThat(summary).doesNotContain("john", "a@example.com", "reply", "Secret");
    }

    @Test
    void mask_sms_andWhatsApp_maskPhone() {
        assertThat(PiiMasking.mask(new SmsRecipient(null, "+15551234590"))).isEqualTo("phone=+1***90");
        assertThat(PiiMasking.mask(new WhatsAppRecipient(null, "+15551234590", "tpl", "en",
                null, List.of("Jane"), null))).isEqualTo("phone=+1***90");
    }

    @Test
    void mask_push_masksDeviceTokenAndShowsTopic() {
        assertThat(PiiMasking.mask(push("fcm-token-abcdefgh-wxyz", null, null))).isEqualTo("token=***wxyz");
        assertThat(PiiMasking.mask(push("short", null, null))).isEqualTo("token=***");
        assertThat(PiiMasking.mask(push(null, "news", null))).isEqualTo("topic=news");
        assertThat(PiiMasking.mask(push(null, null, "'a' in topics"))).isEqualTo("condition=(set)");
        assertThat(PiiMasking.mask(push(null, null, null))).isEqualTo("token=***");
    }

    @Test
    void mask_push_masksFidAndCountsDeviceTokens() {
        assertThat(PiiMasking.mask(pushTargets(null, "fid-0123456789-wxyz", null))).isEqualTo("fid=***wxyz");
        assertThat(PiiMasking.mask(pushTargets(null, "short", null))).isEqualTo("fid=***");
        assertThat(PiiMasking.mask(pushTargets(null, null, List.of("t-1", "t-2", "t-3")))).isEqualTo("tokens=3");
        // An empty token list is no target; the next one in line is used.
        assertThat(PiiMasking.mask(pushTargets(null, "fid-0123456789-wxyz", List.of()))).isEqualTo("fid=***wxyz");
        // A single token takes precedence, as in 1.1.
        assertThat(PiiMasking.mask(pushTargets("fcm-token-abcdefgh-wxyz", "fid-0123456789-abcd", List.of("t-1"))))
                .isEqualTo("token=***wxyz");
    }

    @Test
    void mask_nullRecipient() {
        assertThat(PiiMasking.mask(null)).isEqualTo("***");
    }

    @Test
    void mask_emailWithNullTo_doesNotThrow() {
        assertThat(PiiMasking.mask(new EmailRecipient(null, null, null, List.of("x@y.z"), null, null)))
                .isEqualTo("to=*** cc=0 bcc=1");
    }

    private static PushRecipient push(String token, String topic, String condition) {
        return new PushRecipient(null, token, topic, condition, "title", "body", null, null, null, null, null);
    }

    private static PushRecipient pushTargets(String token, String fid, List<String> tokens) {
        return new PushRecipient(null, token, null, null, "title", "body", null, null, null, null, null,
                fid, tokens);
    }

    // ---------- redact ----------

    @Test
    void redact_masksEmailsInFreeText() {
        String text = "Email address is not verified. The following identities failed the check:"
                + " john.doe@example.com, <jane@corp.example.org>.";

        assertThat(PiiMasking.redact(text)).isEqualTo("Email address is not verified. The following identities"
                + " failed the check: j***@example.com, <j***@corp.example.org>.");
    }

    @Test
    void redact_masksUnicodeEmails() {
        assertThat(PiiMasking.redact("rejected: jörg@bücher.de")).isEqualTo("rejected: j***@bücher.de");
    }

    @Test
    void redact_masksE164LikeNumbers() {
        assertThat(PiiMasking.redact("The 'To' number +15551234590 is not a valid phone number."))
                .isEqualTo("The 'To' number +1***90 is not a valid phone number.");
        assertThat(PiiMasking.redact("to=4155551234;")).isEqualTo("to=4***34;");
    }

    @Test
    void redact_leavesIdentifiersAndShortNumbersAlone() {
        String text = "HTTP 400 code 21211 sid SM1234567890abcdef op 123e4567-e89b-12d3-a456-426614174000"
                + " took 1500 ms";
        assertThat(PiiMasking.redact(text)).isEqualTo(text);
    }

    @Test
    void redact_masksEmailWithDigitsOnlyLocalPartOnce() {
        assertThat(PiiMasking.redact("bad 15551234590@sms.example.com"))
                .isEqualTo("bad 1***@sms.example.com");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void redact_nullAndEmpty_passThrough(String input) {
        assertThat(PiiMasking.redact(input)).isEqualTo(input);
    }

    @Test
    void redact_textWithoutPii_isUnchanged() {
        assertThat(PiiMasking.redact("Connection reset by peer")).isEqualTo("Connection reset by peer");
    }

    @Test
    void redact_replacementCharactersAreLiteral() {
        // '$' and '\' in the masked output must not be read as group references.
        assertThat(PiiMasking.redact("x $1 \\ john@example.com")).isEqualTo("x $1 \\ j***@example.com");
    }
}
