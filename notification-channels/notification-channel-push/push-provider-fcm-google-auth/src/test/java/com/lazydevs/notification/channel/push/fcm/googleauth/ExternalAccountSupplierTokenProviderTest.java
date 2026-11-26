package com.lazydevs.notification.channel.push.fcm.googleauth;

import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.channel.push.fcm.FcmAccessToken;
import com.lazydevs.notification.channel.push.fcm.FcmAccessTokenProvider;
import com.lazydevs.notification.channel.push.fcm.FcmAuthenticationException;
import com.lazydevs.notification.channel.push.fcm.FcmPushProviderAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.lazydevs.notification.channel.push.fcm.googleauth.GoogleStubServer.AUDIENCE;
import static com.lazydevs.notification.channel.push.fcm.googleauth.GoogleStubServer.IMPERSONATION_PATH;
import static com.lazydevs.notification.channel.push.fcm.googleauth.GoogleStubServer.STS_PATH;
import static com.lazydevs.notification.channel.push.fcm.googleauth.GoogleStubServer.SUBJECT_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Workload identity federation with an {@link FcmSubjectTokenSupplier}: no credential file,
 * the supplier's token exchanged at STS and, with an email, an impersonated service account.
 */
class ExternalAccountSupplierTokenProviderTest {

    private static final String HOST = "wif.invalid";
    private static final Duration MARGIN = Duration.ofMinutes(5);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final GoogleStubServer stub = new GoogleStubServer();
    private final StubRoutingTransport transport = new StubRoutingTransport(stub);

    @AfterEach
    void tearDown() {
        stub.close();
    }

    /** A supplier aimed at the stub; counts how often its token is asked for. */
    static class StubSupplier implements FcmSubjectTokenSupplier {

        final AtomicInteger calls = new AtomicInteger();
        private final String name;
        private final boolean impersonate;

        StubSupplier(String name, boolean impersonate) {
            this.name = name;
            this.impersonate = impersonate;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String audience() {
            return AUDIENCE;
        }

        @Override
        public String tokenUrl() {
            return "https://" + HOST + STS_PATH;
        }

        @Override
        public String serviceAccountImpersonationUrl() {
            return impersonate ? "https://" + HOST + IMPERSONATION_PATH : null;
        }

        @Override
        public String subjectToken() throws IOException {
            calls.incrementAndGet();
            return SUBJECT_TOKEN;
        }
    }

    private FcmAccessTokenProvider create(String credentials, FcmSubjectTokenSupplier... suppliers) {
        return new GoogleAuthTokenProviderFactory(List.of(suppliers))
                .create(credentials, transport, Clock.systemUTC());
    }

    @Test
    void theSuppliersToken_isExchangedAtSts_thenTheServiceAccountIsImpersonated() {
        StubSupplier supplier = new StubSupplier("azure-mi", true);

        FcmAccessTokenProvider provider = create("external-account-supplier:azure-mi", supplier);
        assertThat(supplier.calls).as("create() asks for no token").hasValue(0);
        FcmAccessToken token = provider.token();

        assertThat(token.value()).startsWith("ya29.impersonated-");
        // The library may ask more than once per refresh; it asks only when it needs a token.
        assertThat(supplier.calls.get()).isPositive();
        int afterFirst = supplier.calls.get();
        assertThat(provider.token()).isEqualTo(token);
        assertThat(supplier.calls).as("a cached token asks for nothing").hasValue(afterFirst);
        assertThat(stub.problems()).isEmpty();
        assertThat(transport.paths()).containsExactly(STS_PATH, IMPERSONATION_PATH);
        assertThat(provider.toString()).contains("external-account-supplier:azure-mi").doesNotContain(SUBJECT_TOKEN);
    }

    @Test
    void withoutImpersonation_theStsTokenIsUsed() {
        StubSupplier supplier = new StubSupplier("direct", false);

        FcmAccessToken token = create(" external-account-supplier: direct ", supplier).token();

        assertThat(token.value()).startsWith("sts-");
        assertThat(transport.paths()).containsExactly(STS_PATH);
    }

    @Test
    void aFailingSupplier_failsTheTokenFetch_withoutItsMessageLeakingATokenAndWithoutHttp() {
        FcmSubjectTokenSupplier failing = new StubSupplier("broken", false) {
            @Override
            public String subjectToken() throws IOException {
                throw new IOException("managed identity endpoint unreachable");
            }
        };

        FcmAccessTokenProvider provider = create("external-account-supplier:broken", failing);

        assertThatThrownBy(provider::token)
                .isInstanceOfSatisfying(FcmAuthenticationException.class,
                        e -> assertThat(e.failureType()).isNotEqualTo(FailureType.PERMANENT))
                .hasMessageContaining("managed identity endpoint unreachable");
        assertThat(transport.count()).isZero();
    }

    @Test
    void anUnknownOrMissingSupplierName_isAConfigurationError() {
        StubSupplier known = new StubSupplier("known", false);

        assertThatThrownBy(() -> create("external-account-supplier:other", known))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("no FcmSubjectTokenSupplier is named 'other'").hasMessageContaining("[known]");
        assertThatThrownBy(() -> create("external-account-supplier:  ", known))
                .isInstanceOf(ProviderConfigurationException.class).hasMessageContaining("needs the name");
        assertThatThrownBy(() -> new GoogleAuthTokenProviderFactory().create("external-account-supplier:x",
                transport, Clock.systemUTC()))
                .isInstanceOf(ProviderConfigurationException.class).hasMessageContaining("declare one as a Spring bean");
    }

    @Test
    void aSupplierWithoutAudience_isAConfigurationError() {
        StubSupplier noAudience = new StubSupplier("no-audience", false) {
            @Override
            public String audience() {
                return " ";
            }
        };

        assertThatThrownBy(() -> create("external-account-supplier:no-audience", noAudience))
                .isInstanceOf(ProviderConfigurationException.class).hasMessageContaining("names no audience");
    }

    @Test
    void duplicateOrBlankSupplierNames_failConstruction() {
        StubSupplier one = new StubSupplier("same", false);
        StubSupplier two = new StubSupplier("same", true);

        assertThatThrownBy(() -> new GoogleAuthTokenProviderFactory(List.of(one, two)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'same'");
        assertThatThrownBy(() -> new GoogleAuthTokenProviderFactory(List.of(new StubSupplier(" ", false))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("has no name");
    }

    @Test
    void theDefaultImpersonationUrl_isIamCredentialsForTheEmail() {
        FcmSubjectTokenSupplier supplier = new FcmSubjectTokenSupplier() {
            @Override
            public String name() {
                return "n";
            }

            @Override
            public String audience() {
                return AUDIENCE;
            }

            @Override
            public String serviceAccountEmail() {
                return "fcm@p.iam.gserviceaccount.com";
            }

            @Override
            public String subjectToken() {
                return SUBJECT_TOKEN;
            }
        };

        assertThat(supplier.serviceAccountImpersonationUrl()).isEqualTo("https://iamcredentials.googleapis.com"
                + "/v1/projects/-/serviceAccounts/fcm@p.iam.gserviceaccount.com:generateAccessToken");
        assertThat(supplier.tokenUrl()).isEqualTo("https://sts.googleapis.com/v1/token");
        assertThat(supplier.subjectTokenType()).isEqualTo("urn:ietf:params:oauth:token-type:jwt");
    }

    @Test
    void theAutoConfiguration_handsEverySupplierBeanToTheFactory() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FcmGoogleAuthAutoConfiguration.class,
                        FcmPushProviderAutoConfiguration.class))
                .withUserConfiguration(Suppliers.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GoogleAuthTokenProviderFactory factory = context.getBean(GoogleAuthTokenProviderFactory.class);
                    assertThat(factory.supports("external-account-supplier:azure-mi")).isTrue();
                    assertThat(factory.create("external-account-supplier:azure-mi", transport, Clock.systemUTC()))
                            .isNotNull();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class Suppliers {

        @Bean
        FcmSubjectTokenSupplier azureManagedIdentity() {
            return new StubSupplier("azure-mi", true);
        }
    }
}
