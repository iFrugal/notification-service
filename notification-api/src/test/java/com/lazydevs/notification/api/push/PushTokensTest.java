package com.lazydevs.notification.api.push;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushTokensTest {

    /** SHA-256 of "abc" (FIPS 180-2 test vector). */
    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void hashIsPrefixAndSixteenHexOfSha256() {
        assertThat(PushTokens.hash("abc")).isEqualTo("sha256:ba7816bf8f01cfea");
        assertThat(PushTokens.sha256Hex("abc")).isEqualTo(ABC_SHA256);
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertThat(PushTokens.hash("  abc\n")).isEqualTo(PushTokens.hash("abc"));
        assertThat(PushTokens.sha256Hex(" abc ")).isEqualTo(ABC_SHA256);
    }

    @Test
    void nullTokenIsRejected() {
        assertThatThrownBy(() -> PushTokens.hash(null)).isInstanceOf(NullPointerException.class);
    }
}
