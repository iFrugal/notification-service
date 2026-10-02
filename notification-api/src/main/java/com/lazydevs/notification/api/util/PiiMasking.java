package com.lazydevs.notification.api.util;

import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.PushRecipient;
import com.lazydevs.notification.api.model.Recipient;
import com.lazydevs.notification.api.model.SmsRecipient;
import com.lazydevs.notification.api.model.WhatsAppRecipient;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masking helpers for recipient data (email addresses, phone numbers, device
 * tokens) in log lines, error messages and audit summaries.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@link #maskEmail(String)} - first character of the local part,
 *       {@code ***}, then {@code @} and the domain:
 *       {@code john.doe@example.com} becomes {@code j***@example.com}.</li>
 *   <li>{@link #maskPhone(String)} - an optional leading {@code +}, the first
 *       digit, {@code ***} and the last two digits:
 *       {@code +15551234590} becomes {@code +1***90}.
 *       Formatting characters are ignored; fewer than six digits mask fully.</li>
 *   <li>{@link #mask(Recipient)} - a one-line summary per recipient type, with
 *       CC and BCC reduced to counts.</li>
 *   <li>{@link #redact(String)} - masks every email address and every
 *       E.164-like number (seven or more digits, optional {@code +}) inside free
 *       text, such as a provider's exception message.</li>
 * </ul>
 *
 * <p>Every method is null-safe and never throws.
 */
public final class PiiMasking {

    /** Replacement for the hidden part of a value. */
    public static final String MASK = "***";

    private static final int MIN_PHONE_DIGITS_SHOWN = 6;
    private static final int MIN_TOKEN_LENGTH_SHOWN = 12;
    private static final int TOKEN_SUFFIX_LENGTH = 4;

    /**
     * An email address inside free text. Unicode letters and digits are allowed
     * in both parts (RFC 6531 addresses); the domain needs no dot so
     * {@code user@localhost} is masked too.
     */
    private static final Pattern EMAIL = Pattern.compile(
            "[\\p{L}\\p{M}\\p{N}._%+-]+@[\\p{L}\\p{M}\\p{N}-]+(?:\\.[\\p{L}\\p{M}\\p{N}-]+)*");

    /**
     * Seven or more digits with an optional leading {@code +}, not glued to a
     * word character or a dash, so identifiers such as UUID groups and Twilio
     * SIDs are left alone.
     */
    private static final Pattern PHONE = Pattern.compile("(?<![\\w+-])\\+?\\d{7,}(?![\\w-])");

    private PiiMasking() {
    }

    /**
     * Mask an email address: {@code john@example.com} becomes {@code j***@example.com}.
     *
     * @param email the address, may be {@code null}
     * @return the masked address, or {@link #MASK} when the value is blank or has no {@code @}
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return MASK;
        }
        String value = email.strip();
        int at = value.lastIndexOf('@');
        if (at < 0) {
            return MASK;
        }
        String local = value.substring(0, at);
        String domain = value.substring(at + 1);
        if (local.isEmpty()) {
            return MASK + "@" + domain;
        }
        // Code point, not char, so a surrogate pair is never split.
        String first = new String(Character.toChars(local.codePointAt(0)));
        return first + MASK + "@" + domain;
    }

    /**
     * Mask a phone number: {@code +15551234590} becomes {@code +1***90}.
     *
     * @param phone the number, may be {@code null} or contain formatting characters
     * @return the masked number, or {@link #MASK} when it has fewer than six digits
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return MASK;
        }
        String value = phone.strip();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        if (digits.length() < MIN_PHONE_DIGITS_SHOWN) {
            return MASK;
        }
        String prefix = value.startsWith("+") ? "+" : "";
        return prefix + digits.charAt(0) + MASK + digits.substring(digits.length() - 2);
    }

    /**
     * One-line masked summary of a recipient, suitable for logs and for
     * {@code NotificationAudit.recipientSummary}.
     *
     * <ul>
     *   <li>email - {@code to=j***@example.com cc=2 bcc=0}</li>
     *   <li>SMS and WhatsApp - {@code phone=+1***90}</li>
     *   <li>push - {@code token=***wxyz}, {@code tokens=3}, {@code fid=***wxyz},
     *       {@code topic=news} or {@code condition=(set)}</li>
     * </ul>
     *
     * @param recipient the recipient, may be {@code null}
     * @return the summary, {@link #MASK} for {@code null}
     */
    public static String mask(Recipient recipient) {
        if (recipient == null) {
            return MASK;
        }
        return switch (recipient) {
            case EmailRecipient email -> "to=" + maskEmail(email.to())
                    + " cc=" + count(email.cc()) + " bcc=" + count(email.bcc());
            case SmsRecipient sms -> "phone=" + maskPhone(sms.phoneNumber());
            case WhatsAppRecipient whatsApp -> "phone=" + maskPhone(whatsApp.phoneNumber());
            case PushRecipient push -> maskPush(push);
        };
    }

    /**
     * Mask every email address and E.164-like number inside free text.
     *
     * @param text the text, may be {@code null}
     * @return the text with recipient data masked, {@code null} for {@code null}
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = EMAIL.matcher(text).replaceAll(m -> Matcher.quoteReplacement(maskEmail(m.group())));
        return PHONE.matcher(out).replaceAll(m -> Matcher.quoteReplacement(maskPhone(m.group())));
    }

    private static String maskPush(PushRecipient push) {
        if (hasText(push.deviceToken())) {
            return "token=" + maskIdentifier(push.deviceToken());
        }
        if (push.deviceTokens() != null && !push.deviceTokens().isEmpty()) {
            return "tokens=" + push.deviceTokens().size();
        }
        if (hasText(push.fid())) {
            return "fid=" + maskIdentifier(push.fid());
        }
        if (hasText(push.topic())) {
            // Topics are broadcast channels, not personal data.
            return "topic=" + push.topic().strip();
        }
        if (hasText(push.condition())) {
            return "condition=(set)";
        }
        return "token=" + MASK;
    }

    /** {@code ***} and the last four characters, or only {@code ***} for a short value. */
    private static String maskIdentifier(String value) {
        String v = value.strip();
        return v.length() >= MIN_TOKEN_LENGTH_SHOWN ? MASK + v.substring(v.length() - TOKEN_SUFFIX_LENGTH) : MASK;
    }

    private static int count(List<String> values) {
        return values == null ? 0 : values.size();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
