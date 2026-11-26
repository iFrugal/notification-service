package com.lazydevs.notification.api.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
