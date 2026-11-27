package com.lazydevs.notification.core.push;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.exception.NotificationException;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.PushRecipient;
import com.lazydevs.notification.api.push.PushDeviceRegistration;
import com.lazydevs.notification.api.push.PushPlatform;
import com.lazydevs.notification.api.push.PushTargeting;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushTargetExpansionTest {

    private final InMemoryPushDeviceRegistry registry =
            new InMemoryPushDeviceRegistry(10, Duration.ofDays(60), Clock.systemUTC());

    private static NotificationRequest request(PushRecipient recipient) {
        return NotificationRequest.builder().notificationType("T").channel(Channel.PUSH).recipient(recipient).build();
    }

    @Test
    void thePolicyMustBeAnyOrAll() {
        assertThat(new PushTargetExpansion(registry, " ALL ").isEnabled()).isTrue();
        assertThatThrownBy(() -> new PushTargetExpansion(registry, "some"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'any' or 'all'");
        assertThat(PushTargetExpansion.disabled().isEnabled()).isFalse();
    }

    @Test
    void appId_limitsTheDevices_andNoMatchIsNoDevices() {
        registry.register(new PushDeviceRegistration(null, "u1", "i1", "recruiter", PushPlatform.ANDROID, "t1",
                null, null, null));
        PushTargetExpansion expansion = new PushTargetExpansion(registry, "all");

        NotificationRequest expanded = expansion.expand(request(PushRecipient.forUser("u1", "recruiter")));

        assertThat(((PushRecipient) expanded.getRecipient()).deviceTokens()).containsExactly("t1");
        assertThat(expanded.getMetadata()).containsEntry(PushTargeting.MULTI_TOKEN_POLICY_METADATA, "all");
        NotificationRequest otherApp = request(PushRecipient.forUser("u1", "candidate"));
        assertThatThrownBy(() -> expansion.expand(otherApp))
                .isInstanceOfSatisfying(NotificationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PushTargeting.NO_DEVICES))
                .hasMessageContaining("for this app");
    }

    @Test
    void anotherChannelsRecipient_passes() {
        NotificationRequest sms = NotificationRequest.builder().notificationType("T").channel(Channel.SMS)
                .recipient(new com.lazydevs.notification.api.model.SmsRecipient(null, "+15550100001")).build();

        assertThat(PushTargetExpansion.disabled().expand(sms)).isSameAs(sms);
    }
}
