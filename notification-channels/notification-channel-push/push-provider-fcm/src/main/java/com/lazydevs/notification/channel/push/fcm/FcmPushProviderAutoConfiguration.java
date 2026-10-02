package com.lazydevs.notification.channel.push.fcm;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

import java.util.Map;

/**
 * Registers the {@code fcmPushProvider} bean whenever this module is on the classpath.
 *
 * <p>There is no class condition: the module needs nothing beyond the JDK and
 * notification-api. The bean is a prototype, because {@code ProviderRegistry}
 * configures one instance per tenant through {@link FcmPushProvider#configure(Map)},
 * so instances must never be shared.
 * There is deliberately no property condition; an unused prototype definition
 * costs nothing, and all settings come from the tenant's provider properties.
 *
 * <p>Collaborators, all optional:
 * <ul>
 *   <li>an {@link FcmHttpTransport} bean replaces {@link JdkFcmHttpTransport#shared()}
 *       when it is the only one (or the primary one); every transport bean can also be
 *       selected per tenant by name with {@code http-transport};</li>
 *   <li>{@link FcmAccessTokenProviderFactory} beans are asked first, then the
 *       {@code META-INF/services} entries, then the built-in service-account factory.</li>
 * </ul>
 */
@AutoConfiguration
public class FcmPushProviderAutoConfiguration implements BeanClassLoaderAware {

    /** Bean name the provider catalog refers to for {@code PUSH:fcm}. */
    public static final String BEAN_NAME = "fcmPushProvider";

    private ClassLoader classLoader = FcmPushProviderAutoConfiguration.class.getClassLoader();

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * A fresh, unconfigured provider per lookup.
     *
     * @param transport       the application's {@link FcmHttpTransport}, if it has exactly one
     * @param namedTransports every {@link FcmHttpTransport} bean by name, for {@code http-transport}
     * @param factories       {@link FcmAccessTokenProviderFactory} beans, in order
     * @return a new provider instance
     */
    @Bean(BEAN_NAME)
    @Scope(BeanDefinition.SCOPE_PROTOTYPE)
    @ConditionalOnMissingBean(name = BEAN_NAME)
    public FcmPushProvider fcmPushProvider(ObjectProvider<FcmHttpTransport> transport,
                                           ObjectProvider<Map<String, FcmHttpTransport>> namedTransports,
                                           ObjectProvider<FcmAccessTokenProviderFactory> factories) {
        return new FcmPushProvider(
                transport.getIfUnique(JdkFcmHttpTransport::shared),
                namedTransports.getIfAvailable(Map::of),
                factories.orderedStream().toList(),
                classLoader);
    }
}
