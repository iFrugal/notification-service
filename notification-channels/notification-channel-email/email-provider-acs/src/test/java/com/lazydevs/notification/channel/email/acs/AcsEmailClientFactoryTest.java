package com.lazydevs.notification.channel.email.acs;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.policy.RetryOptions;
import com.azure.identity.DefaultAzureCredential;
import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.FilteredClassLoader;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AcsEmailClientFactory}: which authentication path is taken, and the
 * fail-fast messages for misconfiguration.
 */
class AcsEmailClientFactoryTest {

    private static final String ENDPOINT = "https://unit.communication.azure.com";
    private static final String SECRET = "c2VjcmV0LWtleQ==";
    private static final String CONNECTION_STRING = "endpoint=" + ENDPOINT + "/;accesskey=" + SECRET;
    private static final ClassLoader LOADER = AcsEmailClientFactoryTest.class.getClassLoader();

    private EmailClientBuilder builder;
    private EmailClient client;

    @BeforeEach
    void setUp() {
        builder = mock(EmailClientBuilder.class, RETURNS_SELF);
        client = mock(EmailClient.class);
        when(builder.buildClient()).thenReturn(client);
    }

    private static AcsEmailProperties settings(String... keyValues) {
        Map<String, Object> map = new HashMap<>();
        map.put("sender", "DoNotReply@example.com");
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return AcsEmailProperties.fromMap(map);
    }

    private AcsEmailClientFactory factory(Map<String, TokenCredential> beans) {
        return new AcsEmailClientFactory(beans, LOADER, () -> builder);
    }

    private EmailClient build(AcsEmailClientFactory factory, AcsEmailProperties settings) {
        return factory.createClient(settings, factory.resolveCredential(settings));
    }

    @Test
    void connectionString_usesConnectionString_jdkTransport_andNoSdkRetries() {
        AcsEmailProperties settings = settings("connection-string", CONNECTION_STRING);

        assertThat(build(factory(Map.of()), settings)).isSameAs(client);

        verify(builder).connectionString(CONNECTION_STRING);
        verify(builder, never()).endpoint(anyString());
        verify(builder, never()).credential(any(TokenCredential.class));
        ArgumentCaptor<HttpClient> http = ArgumentCaptor.forClass(HttpClient.class);
        verify(builder).httpClient(http.capture());
        assertThat(http.getValue().getClass().getName()).startsWith("com.azure.core.http.jdk.httpclient.");
        ArgumentCaptor<RetryOptions> retry = ArgumentCaptor.forClass(RetryOptions.class);
        verify(builder).retryOptions(retry.capture());
        assertThat(retry.getValue().getExponentialBackoffOptions().getMaxRetries()).isZero();
    }

    @Test
    void sdkRetries_isPassedToTheRetryPolicy() {
        build(factory(Map.of()), settings("connection-string", CONNECTION_STRING, "sdk-retries", "3"));

        ArgumentCaptor<RetryOptions> retry = ArgumentCaptor.forClass(RetryOptions.class);
        verify(builder).retryOptions(retry.capture());
        assertThat(retry.getValue().getExponentialBackoffOptions().getMaxRetries()).isEqualTo(3);
    }

    @Test
    void connectionString_winsOverEndpointAndCredential() {
        TokenCredential bean = mock(TokenCredential.class);
        AcsEmailProperties settings = settings("connection-string", CONNECTION_STRING,
                "endpoint", ENDPOINT, "credential", "myCredential");

        AcsEmailClientFactory factory = factory(Map.of("myCredential", bean));
        assertThat(factory.resolveCredential(settings)).isNull();
        factory.createClient(settings, null);

        verify(builder).connectionString(CONNECTION_STRING);
        verify(builder, never()).credential(any(TokenCredential.class));
    }

    @Test
    void endpointWithNamedCredentialBean() {
        TokenCredential a = mock(TokenCredential.class);
        TokenCredential b = mock(TokenCredential.class);

        build(factory(Map.of("credA", a, "credB", b)), settings("endpoint", ENDPOINT, "credential", "credB"));

        verify(builder).endpoint(ENDPOINT);
        verify(builder).credential(b);
        verify(builder, never()).connectionString(anyString());
    }

    @Test
    void endpointWithoutCredential_usesTheOnlyTokenCredentialBean() {
        TokenCredential only = mock(TokenCredential.class);

        build(factory(Map.of("only", only)), settings("endpoint", ENDPOINT));

        verify(builder).credential(only);
    }

    @Test
    void endpointWithoutCredential_andNoUniqueBean_fails() {
        Map<String, TokenCredential> two = Map.of("a", mock(TokenCredential.class), "b", mock(TokenCredential.class));

        assertThatThrownBy(() -> factory(two).resolveCredential(settings("endpoint", ENDPOINT)))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("no credential was selected");
        assertThatThrownBy(() -> factory(null).resolveCredential(settings("endpoint", ENDPOINT)))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("no credential was selected");
    }

    @Test
    void endpointWithDefaultCredential_usesDefaultAzureCredential() {
        AcsEmailClientFactory factory = factory(null);

        TokenCredential credential = factory.resolveCredential(settings("endpoint", ENDPOINT, "credential", "Default"));

        assertThat(credential).isInstanceOf(DefaultAzureCredential.class);
        factory.createClient(settings("endpoint", ENDPOINT, "credential", "default"), credential);
        verify(builder).credential(credential);
    }

    @Test
    void defaultCredential_withoutAzureIdentity_failsWithClearMessage() {
        AcsEmailClientFactory factory = new AcsEmailClientFactory(null,
                new FilteredClassLoader("com.azure.identity"), () -> builder);

        assertThatThrownBy(() -> factory.resolveCredential(settings("endpoint", ENDPOINT, "credential", "default")))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("needs com.azure:azure-identity on the classpath");
    }

    @Test
    void namedCredential_outsideSpring_failsWithClearMessage() {
        assertThatThrownBy(() -> factory(null).resolveCredential(
                settings("endpoint", ENDPOINT, "credential", "myCredential")))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("outside the Spring context");
    }

    @Test
    void unknownCredentialBean_listsAvailableNames() {
        Map<String, TokenCredential> beans = Map.of("credA", mock(TokenCredential.class));

        assertThatThrownBy(() -> factory(beans).resolveCredential(
                settings("endpoint", ENDPOINT, "credential", "missing")))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("no TokenCredential bean named 'missing'")
                .hasMessageContaining("[credA]");
    }

    @Test
    void realBuilder_buildsClientFromConnectionString_withoutNetwork() {
        AcsEmailClientFactory real = new AcsEmailClientFactory(null, LOADER);
        AcsEmailProperties settings = settings("connection-string", CONNECTION_STRING);

        assertThat(real.createClient(settings, real.resolveCredential(settings))).isNotNull();
    }

    @Test
    void realBuilder_malformedConnectionString_failsWithoutLeakingTheKey() {
        AcsEmailClientFactory real = new AcsEmailClientFactory(null, LOADER);
        AcsEmailProperties settings = settings("connection-string", "garbage-" + SECRET);

        assertThatThrownBy(() -> real.createClient(settings, null))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("connection-string")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void realBuilder_connectionStringWithoutEndpoint_failsClearly() {
        AcsEmailClientFactory real = new AcsEmailClientFactory(null, LOADER);
        AcsEmailProperties settings = settings("connection-string", "accesskey=" + SECRET);

        assertThatThrownBy(() -> real.createClient(settings, null))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("endpoint")
                .hasMessageNotContaining(SECRET);
    }
}
