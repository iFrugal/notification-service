package com.lazydevs.notification.channel.push.fcm;

import com.lazydevs.notification.api.push.PushTokens;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the target hash format that logs, results and delivery events carry since 1.2.0. */
class FcmJsonTest {

    @Test
    void targetHashKeepsItsFormat() {
        // SHA-256("abc") starts with ba7816bf8f01cfea (FIPS 180-2 test vector).
        assertThat(FcmJson.targetHash("abc")).isEqualTo("sha256:ba7816bf8f01cfea");
    }

    @Test
    void targetHashIsThePushTokensHash() {
        String token = "dGVzdC10b2tlbg:APA91bH-example";
        assertThat(FcmJson.targetHash(token)).isEqualTo(PushTokens.hash(token)).hasSize(23);
    }
}
