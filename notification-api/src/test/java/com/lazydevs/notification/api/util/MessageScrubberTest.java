package com.lazydevs.notification.api.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class MessageScrubberTest {

    private final MessageScrubber scrubber = MessageScrubber.placeholders();

    @Test
    void replacesEmailsAndPhoneNumbersWithPlaceholders() {
        assertThat(scrubber.scrub("Mailbox jane.doe@example.com and +4915112345678 rejected"))
                .isEqualTo("Mailbox [email] and [phone] rejected");
    }

    @Test
    void leavesIdentifiersAndShortNumbersAlone() {
        assertThat(scrubber.scrub("op 3f2a-1234567-ab failed with code 550"))
                .isEqualTo("op 3f2a-1234567-ab failed with code 550");
    }

    @Test
    void isNullSafeAndIdempotent() {
        assertThat(scrubber.scrub(null)).isNull();
        assertThat(scrubber.scrub("")).isEmpty();
        String once = scrubber.scrub("a@b.example 5551234567");
        assertThat(scrubber.scrub(once)).isEqualTo(once).isEqualTo("[email] [phone]");
    }

    @Test
    void isLinearOnLongAdversarialInput() {
        String percents = "%".repeat(200_000);
        String dots = "a.".repeat(100_000) + "@";
        String ats = "a@".repeat(100_000);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(scrubber.scrub(percents)).isEqualTo(percents);
            assertThat(scrubber.scrub(dots)).isEqualTo(dots);
            assertThat(scrubber.scrub(ats)).isEqualTo("[email]@".repeat(50_000));
            assertThat(scrubber.scrub(percents + "x@y.example")).isEqualTo("[email]");
        });
    }

    @Test
    void findsTheSameAddressesAsTheRegularExpression() {
        Pattern email = Pattern.compile(
                "[\\p{L}\\p{M}\\p{N}._%+-]+@[\\p{L}\\p{M}\\p{N}-]+(?:\\.[\\p{L}\\p{M}\\p{N}-]+)*");
        String[] alphabet = {"a", "Z", "7", "\u00e9", "\u0301", "\u00b2", "\ud835\udc9c", ".", "_", "%", "+", "-",
                "@", "@", " ", ",", "<", "\ud83d\ude00", "\n"};
        Random random = new Random(42);
        for (int n = 0; n < 20_000; n++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(24);
            for (int i = 0; i < length; i++) {
                text.append(alphabet[random.nextInt(alphabet.length)]);
            }
            String input = text.toString();
            String expected = email.matcher(input).replaceAll(m -> Matcher.quoteReplacement("<" + m.group() + ">"));

            assertThat(PiiMasking.replaceEmails(input, address -> "<" + address + ">")).as(input).isEqualTo(expected);
        }
    }

    @Test
    void redactStillMasksEveryAddress() {
        assertThat(PiiMasking.redact("from jane.doe@example.com, to %x@host and a@b@c.d"))
                .isEqualTo("from j***@example.com, to %***@host and a***@b@c.d");
    }
}
