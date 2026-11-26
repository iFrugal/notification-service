package com.lazydevs.notification.api.util;

import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.PushRecipient;
import com.lazydevs.notification.api.model.Recipient;
import com.lazydevs.notification.api.model.SmsRecipient;
import com.lazydevs.notification.api.model.WhatsAppRecipient;

import java.util.List;
import java.util.function.UnaryOperator;
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

    /** Characters other than letters, marks and numbers allowed in an email's local part. */
    private static final String LOCAL_PART_SYMBOLS = "._%+-";
    /** {@link #domainEnd} result when no domain follows the {@code @}. */
    private static final int NO_DOMAIN = -1;

    /**
     * Seven or more digits with an optional leading {@code +}, not glued to a
     * word character or a dash, so identifiers such as UUID groups and Twilio
     * SIDs are left alone.
     */
    static final Pattern PHONE = Pattern.compile("(?<![\\w+-])\\+?\\d{7,}(?![\\w-])");

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
        String out = replaceEmails(text, PiiMasking::maskEmail);
        return PHONE.matcher(out).replaceAll(m -> Matcher.quoteReplacement(maskPhone(m.group())));
    }

    /**
     * Replace every email address inside free text. An address is what the regular
     * expression {@code [\p{L}\p{M}\p{N}._%+-]+@[\p{L}\p{M}\p{N}-]+(?:\.[\p{L}\p{M}\p{N}-]+)*}
     * matches: Unicode letters and digits are allowed in both parts (RFC 6531
     * addresses) and the domain needs no dot, so {@code user@localhost} counts too.
     * A scanner finds the same matches as that expression in linear time; the
     * expression itself backtracks quadratically on a long run of local-part
     * characters without an {@code @}.
     *
     * @param text        the text, not {@code null}
     * @param replacement maps each address to its replacement
     * @return the text with every address replaced
     */
    static String replaceEmails(String text, UnaryOperator<String> replacement) {
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        int at = text.indexOf('@');
        while (at >= 0) {
            int start = localPartStart(text, at, copied);
            int end = start < at ? domainEnd(text, at + 1) : NO_DOMAIN;
            if (end == NO_DOMAIN) {
                at = text.indexOf('@', at + 1);
            } else {
                out.append(text, copied, start).append(replacement.apply(text.substring(start, end)));
                copied = end;
                at = text.indexOf('@', end);
            }
        }
        return out.append(text, copied, text.length()).toString();
    }

    /** Start of the local part that ends at {@code at}, not before {@code floor}; {@code at} when empty. */
    private static int localPartStart(String text, int at, int floor) {
        int i = at;
        while (i > floor) {
            int cp = text.codePointBefore(i);
            int width = Character.charCount(cp);
            if (i - width < floor || !(isLetterMarkOrNumber(cp) || LOCAL_PART_SYMBOLS.indexOf(cp) >= 0)) {
                break;
            }
            i -= width;
        }
        return i;
    }

    /** End of the domain that starts at {@code from}, or {@link #NO_DOMAIN} when there is none. */
    private static int domainEnd(String text, int from) {
        int end = skipDomainLabel(text, from);
        if (end == from) {
            return NO_DOMAIN;
        }
        while (end < text.length() && text.charAt(end) == '.') {
            int next = skipDomainLabel(text, end + 1);
            if (next == end + 1) {
                break;
            }
            end = next;
        }
        return end;
    }

    private static int skipDomainLabel(String text, int from) {
        int i = from;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (!(isLetterMarkOrNumber(cp) || cp == '-')) {
                break;
            }
            i += Character.charCount(cp);
        }
        return i;
    }

    /** {@code \p{L}}, {@code \p{M}} or {@code \p{N}}. */
    private static boolean isLetterMarkOrNumber(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
                 Character.MODIFIER_LETTER, Character.OTHER_LETTER,
                 Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
                 Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true;
            default -> false;
        };
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
