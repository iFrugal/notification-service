package com.lazydevs.notification.channel.push.fcm.googleauth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import com.lazydevs.notification.channel.push.fcm.FcmAccessTokenProviderFactory;
import com.lazydevs.notification.channel.push.fcm.FcmSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleAuthTokenProviderFactoryTest {

    private final GoogleAuthTokenProviderFactory factory = new GoogleAuthTokenProviderFactory();

    @TempDir
    Path dir;

    @Test
    void isRegisteredForServiceLoader() {
        assertThat(ServiceLoader.load(FcmAccessTokenProviderFactory.class).stream().map(ServiceLoader.Provider::type))
                .contains(GoogleAuthTokenProviderFactory.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"adc", "ADC", " adc ", "external-account:/etc/wif.json", "External-Account:/x"})
    void supports_adcAndExternalAccount(String credentials) {
        assertThat(factory.supports(credentials)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/etc/key.json", "{\"type\":\"service_account\"}", "adc.json", "file:/x", ""})
    void doesNotSupport_serviceAccountKeys(String credentials) {
        assertThat(factory.supports(credentials)).isFalse();
        assertThat(factory.supports(null)).isFalse();
    }

    @Test
    void externalAccountWithoutAPath_failsConfiguration() {
        assertThatThrownBy(() -> factory.create("external-account: ", request -> null, Clock.systemUTC()))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("needs the path of an external account credential configuration file");
    }

    @Test
    void createFromSettings_usesTheTenantTimeoutAndWarnsThatTokenEndpointDoesNotApply() throws IOException {
        Path subject = Files.writeString(dir.resolve("t"), GoogleStubServer.SUBJECT_TOKEN);
        Path config = Files.writeString(dir.resolve("wif.json"),
                GoogleStubServer.externalAccountJson("wif.invalid", subject.toString(), false));
        FcmSettings settings = FcmSettings.fromMap(Map.of("credentials", "external-account:" + config,
                "token-endpoint", "http://127.0.0.1:1/token", "timeout", "3s"));
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger(GoogleAuthTokenProviderFactory.class);
        appender.start();
        logger.addAppender(appender);
        try {
            var provider = factory.create(settings, request -> null, Clock.systemUTC());

            assertThat(provider).isInstanceOf(GoogleAuthTokenProvider.class)
                    .hasToString("GoogleAuthTokenProvider[external-account:" + config + "]");
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(m -> assertThat(m).contains("'token-endpoint' is ignored"));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
