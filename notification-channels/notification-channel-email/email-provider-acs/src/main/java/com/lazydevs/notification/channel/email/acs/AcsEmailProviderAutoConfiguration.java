package com.lazydevs.notification.channel.email.acs;

import com.azure.communication.email.EmailClient;
import com.azure.core.credential.TokenCredential;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

import java.util.Map;

/**
 * Registers the {@code acsEmailProvider} bean whenever this module and the ACS
 * SDK are on the classpath.
 *
 * <p>The bean is a prototype: {@code ProviderRegistry} configures one instance
 * per tenant through {@link AcsEmailProvider#configure(Map)}, so instances must
 * never be shared.
 * There is deliberately no property condition; an unused prototype definition
 * costs nothing, and all settings come from the tenant's provider properties.
 */
@AutoConfiguration
@ConditionalOnClass(EmailClient.class)
public class AcsEmailProviderAutoConfiguration implements BeanClassLoaderAware {

    /** Bean name the provider catalog refers to for {@code EMAIL:acs}. */
    public static final String BEAN_NAME = "acsEmailProvider";

    private ClassLoader classLoader = AcsEmailProviderAutoConfiguration.class.getClassLoader();

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * A fresh, unconfigured provider per lookup.
     *
     * @param credentials every {@link TokenCredential} bean by name, so a tenant can
     *                    select one with {@code credential=<bean name>}
     * @return a new provider instance
     */
    @Bean(BEAN_NAME)
    @Scope(BeanDefinition.SCOPE_PROTOTYPE)
    @ConditionalOnMissingBean(name = BEAN_NAME)
    public AcsEmailProvider acsEmailProvider(ObjectProvider<Map<String, TokenCredential>> credentials) {
        return new AcsEmailProvider(credentials.getIfAvailable(Map::of), classLoader);
    }
}
