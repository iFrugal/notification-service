package com.lazydevs.notification.core.provider;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.EmailProvider;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.ChannelDisabledException;
import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import com.lazydevs.notification.api.exception.ProviderNotFoundException;
import com.lazydevs.notification.api.exception.ProviderRequiredException;
import com.lazydevs.notification.api.exception.TenantNotFoundException;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.ChannelConfig;
import com.lazydevs.notification.core.config.NotificationProperties.ProviderConfig;
import com.lazydevs.notification.core.config.NotificationProperties.TenantConfig;
import com.lazydevs.notification.core.provider.ProviderRegistry.PendingState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProviderRegistry}: the default-tenant provider
 * fallback (DD-28) and lazy provider initialisation (DD-29), with a fake
 * provider so the lifecycle calls can be counted.
 */
class ProviderRegistryTest {

    /** Fake email provider: counts lifecycle calls and fails configure() on demand. */
    static final class FakeEmailProvider implements EmailProvider {
        final AtomicInteger configured = new AtomicInteger();
        final AtomicInteger destroyed = new AtomicInteger();
        volatile String configError;
        volatile CountDownLatch configureGate;

        @Override
        public String getProviderName() {
            return "fake";
        }

        @Override
        public void configure(Map<String, Object> properties) {
            awaitGate();
            configured.incrementAndGet();
            if (configError != null) {
                throw new IllegalStateException(configError);
            }
        }

        private void awaitGate() {
            CountDownLatch gate = configureGate;
            if (gate == null) {
                return;
            }
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void destroy() {
            destroyed.incrementAndGet();
        }

        @Override
        public SendResult send(NotificationRequest request, RenderedContent content) {
            return SendResult.success("sent-by-fake");
        }
    }

    private NotificationProperties properties;
    private ProviderResolver resolver;
    private final List<FakeEmailProvider> built = new ArrayList<>();
    private volatile String nextConfigError;
    private volatile CountDownLatch nextGate;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.getTenants().put("default", tenantWithEmail());
        properties.getTenants().put("acme", tenantWithEmail());
        resolver = mock(ProviderResolver.class);
        when(resolver.resolve(anyString(), anyString(), any(Channel.class), any(ProviderConfig.class), any()))
                .thenAnswer(inv -> {
                    FakeEmailProvider p = new FakeEmailProvider();
                    p.configError = nextConfigError;
                    p.configureGate = nextGate;
                    synchronized (built) {
                        built.add(p);
                    }
                    return p;
                });
    }

    private static TenantConfig tenantWithEmail() {
        ChannelConfig email = new ChannelConfig();
        email.getProviders().put("fake", new ProviderConfig());
        TenantConfig tenant = new TenantConfig();
        tenant.getChannels().put("email", email);
        return tenant;
    }

    private ProviderRegistry startedRegistry() {
        ProviderRegistry registry = new ProviderRegistry(properties, resolver);
        registry.initializeProviders();
        return registry;
    }

    @Nested
    class TenantFallback {

        @Test
        void off_unknownTenantStillFailsWithTenantNotFound() {
            ProviderRegistry registry = startedRegistry();

            assertThatThrownBy(() -> registry.getProvider("globex", Channel.EMAIL, null))
                    .isInstanceOf(TenantNotFoundException.class)
                    .hasMessageContaining("globex");
            assertThat(registry.isProviderRequiredInRequest("globex", Channel.EMAIL)).isTrue();
        }

        @Test
        void on_unknownTenantUsesTheDefaultTenantsProviderInstance() {
            properties.getTenantFallback().setEnabled(true);
            ProviderRegistry registry = startedRegistry();

            NotificationProvider forUnknown = registry.getProvider("globex", Channel.EMAIL, null);

            assertThat(forUnknown).isSameAs(registry.getProvider("default", Channel.EMAIL, null));
            assertThat(registry.isProviderRequiredInRequest("globex", Channel.EMAIL)).isFalse();
        }

        @Test
        void on_configuredTenantKeepsItsOwnProvider() {
            properties.getTenantFallback().setEnabled(true);
            ProviderRegistry registry = startedRegistry();

            assertThat(registry.getProvider("acme", Channel.EMAIL, null))
                    .isNotSameAs(registry.getProvider("default", Channel.EMAIL, null));
        }

        @Test
        void on_listingsNeverShowThePhantomTenant() {
            properties.getTenantFallback().setEnabled(true);
            ProviderRegistry registry = startedRegistry();

            registry.getProvider("globex", Channel.EMAIL, null);

            assertThat(registry.getAllProviders()).containsOnlyKeys("default:email:fake", "acme:email:fake");
            assertThat(registry.getUninitializedProviders()).isEmpty();
        }

        @Test
        void on_missingDefaultTenantStillFailsWithTenantNotFound() {
            properties.getTenantFallback().setEnabled(true);
            properties.setDefaultTenant("absent");
            ProviderRegistry registry = startedRegistry();

            assertThatThrownBy(() -> registry.getProvider("globex", Channel.EMAIL, null))
                    .isInstanceOf(TenantNotFoundException.class)
                    .hasMessageContaining("globex");
            assertThat(registry.isProviderRequiredInRequest("globex", Channel.EMAIL)).isTrue();
        }

        @Test
        void on_nullDefaultTenantStillFailsWithTenantNotFound() {
            properties.getTenantFallback().setEnabled(true);
            properties.setDefaultTenant(null);
            ProviderRegistry registry = startedRegistry();

            assertThatThrownBy(() -> registry.getProvider("globex", Channel.EMAIL, null))
                    .isInstanceOf(TenantNotFoundException.class);
        }

        @Test
        void on_channelNotConfiguredOnDefaultTenant_isDisabledForTheRequestedTenant() {
            properties.getTenantFallback().setEnabled(true);
            ProviderRegistry registry = startedRegistry();

            assertThatThrownBy(() -> registry.getProvider("globex", Channel.SMS, null))
                    .isInstanceOf(ChannelDisabledException.class)
                    .hasMessageContaining("globex");
            assertThat(registry.isProviderRequiredInRequest("globex", Channel.SMS)).isTrue();
        }
    }

    @Nested
    class EagerInit {

        @Test
        void startupBuildsEveryProvider() {
            ProviderRegistry registry = startedRegistry();

            assertThat(built).hasSize(2);
            assertThat(registry.getAllProviders()).hasSize(2);
            assertThat(registry.getUninitializedProviders()).isEmpty();
        }

        @Test
        void startupFailsOnABadProvider() {
            nextConfigError = "endpoint is required";
            ProviderRegistry registry = new ProviderRegistry(properties, resolver);

            assertThatThrownBy(registry::initializeProviders)
                    .isInstanceOf(ProviderConfigurationException.class)
                    .hasMessageContaining("endpoint is required");
        }

        @Test
        void unknownProviderName_isNotFound() {
            ProviderRegistry registry = startedRegistry();

            assertThatThrownBy(() -> registry.getProvider("acme", Channel.EMAIL, "other"))
                    .isInstanceOf(ProviderNotFoundException.class);
        }

        @Test
        void multipleProvidersWithoutDefault_requireTheProviderInTheRequest() {
            properties.getTenants().get("acme").getChannels().get("email")
                    .getProviders().put("second", new ProviderConfig());
            ProviderRegistry registry = startedRegistry();

            assertThatThrownBy(() -> registry.getProvider("acme", Channel.EMAIL, null))
                    .isInstanceOf(ProviderRequiredException.class);
        }

        @Test
        void disabledChannel_isNeitherBuiltNorListed() {
            properties.getTenants().get("acme").getChannels().get("email").setEnabled(false);
            properties.getProviders().setLazyInit(true);
            ProviderRegistry registry = startedRegistry();

            assertThat(registry.getUninitializedProviders()).containsOnlyKeys("default:email:fake");
            assertThatThrownBy(() -> registry.getProvider("acme", Channel.EMAIL, null))
                    .isInstanceOf(ChannelDisabledException.class);
        }
    }

    @Nested
    class LazyInit {

        @BeforeEach
        void enableLazyInit() {
            properties.getProviders().setLazyInit(true);
        }

        @Test
        void startupBuildsNothingAndSucceedsWithABrokenProvider() {
            nextConfigError = "endpoint is required";
            ProviderRegistry registry = startedRegistry();

            verify(resolver, never()).resolve(anyString(), anyString(), any(Channel.class),
                    any(ProviderConfig.class), any());
            assertThat(registry.getAllProviders()).isEmpty();
            assertThat(registry.getUninitializedProviders()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "default:email:fake", PendingState.NOT_INITIALIZED,
                    "acme:email:fake", PendingState.NOT_INITIALIZED));
        }

        @Test
        void firstLookupBuildsOnceAndLaterLookupsReuseIt() {
            ProviderRegistry registry = startedRegistry();

            NotificationProvider first = registry.getProvider("acme", Channel.EMAIL, null);
            NotificationProvider second = registry.getProvider("acme", Channel.EMAIL, "fake");

            assertThat(first).isInstanceOf(FakeEmailProvider.class).isSameAs(second);
            assertThat(((FakeEmailProvider) first).configured).hasValue(1);
            verify(resolver, times(1)).resolve(eq("acme"), eq("fake"), eq(Channel.EMAIL),
                    any(ProviderConfig.class), any());
            assertThat(registry.getAllProviders()).containsOnlyKeys("acme:email:fake");
            assertThat(registry.getUninitializedProviders()).containsOnlyKeys("default:email:fake");
        }

        @Test
        void concurrentFirstLookupsShareOneBuild() throws Exception {
            nextGate = new CountDownLatch(1);
            ProviderRegistry registry = startedRegistry();
            int threads = 16;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                CountDownLatch ready = new CountDownLatch(threads);
                List<Future<NotificationProvider>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        return registry.getProvider("acme", Channel.EMAIL, null);
                    }));
                }
                ready.await(5, TimeUnit.SECONDS);
                // Let the waiting callers pile up behind the build in progress.
                Thread.sleep(100);
                nextGate.countDown();

                NotificationProvider expected = results.get(0).get(5, TimeUnit.SECONDS);
                for (Future<NotificationProvider> result : results) {
                    assertThat(result.get(5, TimeUnit.SECONDS)).isSameAs(expected);
                }
                assertThat(built).hasSize(1);
                assertThat(built.get(0).configured).hasValue(1);
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        void failedBuild_returnsATransientProviderNotConfiguredFailure() {
            nextConfigError = "endpoint is required";
            ProviderRegistry registry = startedRegistry();

            NotificationProvider provider = registry.getProvider("acme", Channel.EMAIL, null);
            SendResult result = provider.send(null, null);

            assertThat(provider).isInstanceOf(UnavailableProvider.class);
            assertThat(provider.getChannel()).isEqualTo(Channel.EMAIL);
            assertThat(provider.getProviderName()).isEqualTo("fake");
            assertThat(provider.isHealthy()).isFalse();
            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ProviderRegistry.PROVIDER_NOT_CONFIGURED);
            assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
            assertThat(result.errorMessage()).contains("fake", "EMAIL", "endpoint is required");
            assertThat(registry.getAllProviders()).isEmpty();
            assertThat(registry.getUninitializedProviders())
                    .containsEntry("acme:email:fake", PendingState.INIT_FAILED);
        }

        @Test
        void failedBuildIsNotCached_theNextLookupBuildsAgain() {
            nextConfigError = "endpoint is required";
            ProviderRegistry registry = startedRegistry();
            assertThat(registry.getProvider("acme", Channel.EMAIL, null)).isInstanceOf(UnavailableProvider.class);
            // Same failure again: still not cached.
            assertThat(registry.getProvider("acme", Channel.EMAIL, null)).isInstanceOf(UnavailableProvider.class);

            nextConfigError = null;
            NotificationProvider provider = registry.getProvider("acme", Channel.EMAIL, null);

            assertThat(provider).isInstanceOf(FakeEmailProvider.class);
            assertThat(built).hasSize(3);
            assertThat(registry.getUninitializedProviders()).doesNotContainKey("acme:email:fake");
        }

        @Test
        void retryOnTheStandIn_rebuildsAndDelegatesOnceTheProviderCanBeBuilt() {
            nextConfigError = "endpoint is required";
            ProviderRegistry registry = startedRegistry();
            NotificationProvider standIn = registry.getProvider("acme", Channel.EMAIL, null);
            assertThat(standIn.send(null, null).errorCode()).isEqualTo(ProviderRegistry.PROVIDER_NOT_CONFIGURED);

            // A retry while the provider still cannot be built fails the same way.
            assertThat(standIn.send(null, null).failureType()).isEqualTo(FailureType.TRANSIENT);
            assertThat(built).hasSize(2);

            nextConfigError = null;
            SendResult retried = standIn.send(null, null);

            assertThat(retried.success()).isTrue();
            assertThat(retried.messageId()).isEqualTo("sent-by-fake");
            assertThat(registry.getProvider("acme", Channel.EMAIL, null)).isSameAs(built.get(2));
        }

        @Test
        void standInConfigureIsANoOp() {
            nextConfigError = "endpoint is required";
            ProviderRegistry registry = startedRegistry();
            NotificationProvider standIn = registry.getProvider("acme", Channel.EMAIL, null);

            standIn.configure(Map.of("endpoint", "x"));

            assertThat(standIn.send(null, null).errorCode()).isEqualTo(ProviderRegistry.PROVIDER_NOT_CONFIGURED);
        }

        @Test
        void unknownProviderName_isStillNotFound() {
            ProviderRegistry registry = startedRegistry();

            assertThatThrownBy(() -> registry.getProvider("acme", Channel.EMAIL, "other"))
                    .isInstanceOf(ProviderNotFoundException.class);
        }

        @Test
        void destroyClosesOnlyTheProvidersThatWereBuilt() {
            ProviderRegistry registry = startedRegistry();
            FakeEmailProvider acme = (FakeEmailProvider) registry.getProvider("acme", Channel.EMAIL, null);

            registry.destroyProviders();

            assertThat(acme.destroyed).hasValue(1);
            assertThat(built).hasSize(1);
            assertThat(registry.getAllProviders()).isEmpty();
        }

        @Test
        void withFallback_unknownTenantBuildsTheDefaultTenantsProvider() {
            properties.getTenantFallback().setEnabled(true);
            ProviderRegistry registry = startedRegistry();

            NotificationProvider provider = registry.getProvider("globex", Channel.EMAIL, null);

            assertThat(provider).isSameAs(registry.getProvider("default", Channel.EMAIL, null));
            verify(resolver).resolve(eq("default"), eq("fake"), eq(Channel.EMAIL), any(ProviderConfig.class), any());
            assertThat(registry.getAllProviders()).containsOnlyKeys("default:email:fake");
        }
    }
}
