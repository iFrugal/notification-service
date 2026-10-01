package com.lazydevs.notification.core.provider;

import com.lazydevs.notification.core.config.NotificationProperties.ChannelConfig;
import com.lazydevs.notification.core.config.NotificationProperties.ProviderConfig;
import com.lazydevs.notification.core.config.NotificationProperties.TenantConfig;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Registers GraalVM reflection hints at build time for every provider class
 * the application's configuration names with {@code fqcn} under
 * {@code notification.tenants}.
 *
 * <p>Those classes are instantiated reflectively by {@link ProviderResolver},
 * which a native image only allows for registered types.
 * Registered in {@code META-INF/spring/aot.factories}.
 */
public class ProviderFqcnAotProcessor implements BeanFactoryInitializationAotProcessor {

    static final String TENANTS_PROPERTY = "notification.tenants";

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
        if (!beanFactory.containsBean(ConfigurableApplicationContext.ENVIRONMENT_BEAN_NAME)) {
            return null;
        }
        Environment environment = beanFactory.getBean(ConfigurableApplicationContext.ENVIRONMENT_BEAN_NAME,
                Environment.class);
        Set<String> classNames = configuredFqcns(Binder.get(environment));
        if (classNames.isEmpty()) {
            return null;
        }
        return (generationContext, code) -> classNames.forEach(
                className -> ProviderRuntimeHints.registerProviderType(generationContext.getRuntimeHints(), className));
    }

    private static Set<String> configuredFqcns(Binder binder) {
        Map<String, TenantConfig> tenants = binder
                .bind(TENANTS_PROPERTY, Bindable.mapOf(String.class, TenantConfig.class))
                .orElse(Map.of());
        Set<String> classNames = new TreeSet<>();
        for (TenantConfig tenant : tenants.values()) {
            for (ChannelConfig channel : tenant.getChannels().values()) {
                for (ProviderConfig provider : channel.getProviders().values()) {
                    if (StringUtils.hasText(provider.getFqcn())) {
                        classNames.add(provider.getFqcn().trim());
                    }
                }
            }
        }
        return classNames;
    }
}
