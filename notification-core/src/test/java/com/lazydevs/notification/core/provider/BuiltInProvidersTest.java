package com.lazydevs.notification.core.provider;

import com.lazydevs.notification.api.Channel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInProvidersTest {

    @Test
    void beanName_followsNamePlusChannelPlusProviderConvention() {
        assertThat(BuiltInProviders.all()).extracting(BuiltInProviders.Entry::beanName).containsExactly(
                "smtpEmailProvider",
                "sesEmailProvider",
                "acsEmailProvider",
                "twilioSmsProvider",
                "snsSmsProvider",
                "twilioWhatsappProvider",
                "metaWhatsappProvider",
                "fcmPushProvider",
                "apnsPushProvider");
    }

    @Test
    void find_distinguishesImplementedFromPlannedProviders() {
        assertThat(BuiltInProviders.find(Channel.EMAIL, "ses")).hasValueSatisfying(entry -> {
            assertThat(entry.implemented()).isTrue();
            assertThat(entry.artifactId()).isEqualTo("email-provider-ses");
        });
        assertThat(BuiltInProviders.find(Channel.SMS, "sns")).hasValueSatisfying(
                entry -> assertThat(entry.implemented()).isFalse());
        assertThat(BuiltInProviders.find(Channel.SMS, "smtp")).isEmpty();
    }
}
