package com.lazydevs.notification.core.provider;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import com.lazydevs.notification.api.exception.ProviderNotFoundException;
import com.lazydevs.notification.core.config.NotificationProperties.ProviderConfig;
import lazydevs.mapper.utils.reflection.ClassUtils;
import lazydevs.mapper.utils.reflection.InitDTO;
import lazydevs.mapper.utils.reflection.ReflectionUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.function.Function;

/**
 * Resolves notification providers from configuration.
 * Uses beanName for Spring beans, fqcn for reflection-based instantiation.
 */
@Slf4j
@Component
public class ProviderResolver {

    private final ApplicationContext applicationContext;

    public ProviderResolver(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Resolve a provider from configuration.
     *
     * Resolution order:
     * 1. beanName specified -> Spring bean lookup
     * 2. fqcn specified -> Class instantiation via reflection
     * 3. Built-in provider by name -> Look up in builtInProviders map
     *
     * @param providerName     the provider name from config
     * @param channel          the channel
     * @param config           the provider configuration
     * @param providerInterface the expected interface class
     * @param builtInProviders map of built-in provider classes
     * @return the resolved provider instance (not yet configured/initialized)
     */
    public <T extends NotificationProvider> T resolve(
            String providerName,
            Channel channel,
            ProviderConfig config,
            Class<T> providerInterface,
            Map<String, Class<? extends NotificationProvider>> builtInProviders) {

        // 1. Try Spring bean lookup
        if (StringUtils.hasText(config.getBeanName())) {
            log.debug("Resolving provider '{}' via beanName: {}", providerName, config.getBeanName());
            return resolveByBeanName(config.getBeanName(), providerInterface, providerName, channel);
        }

        // 2. Try FQCN instantiation
        if (StringUtils.hasText(config.getFqcn())) {
            log.debug("Resolving provider '{}' via fqcn: {}", providerName, config.getFqcn());
            return resolveByFqcn(config.getFqcn(), providerInterface, providerName, channel);
        }

        // 3. Try built-in provider lookup
        String builtInKey = channel.name() + ":" + providerName;
        Class<? extends NotificationProvider> builtInClass = builtInProviders.get(builtInKey);
        if (builtInClass != null) {
            log.debug("Resolving provider '{}' as built-in: {}", providerName, builtInClass.getName());
            return instantiateClass(builtInClass, providerInterface, providerName, channel);
        }

        // 4. Not found
        throw new ProviderNotFoundException(
                String.format("Provider '%s' not found for channel '%s'. " +
                        "For external providers, specify 'beanName' (for Spring beans) or 'fqcn' (for class instantiation). " +
                        "Available built-in providers for %s: %s",
                        providerName, channel, channel, getAvailableBuiltIns(channel, builtInProviders)));
    }

    private <T extends NotificationProvider> T resolveByBeanName(
            String beanName, Class<T> providerInterface, String providerName, Channel channel) {
        try {
            Object bean = applicationContext.getBean(beanName);
            if (providerInterface.isInstance(bean)) {
                return providerInterface.cast(bean);
            }
            throw new ProviderConfigurationException(providerName, channel.name(),
                    String.format("Bean '%s' is not an instance of %s", beanName, providerInterface.getName()));
        } catch (NoSuchBeanDefinitionException e) {
            throw new ProviderNotFoundException(
                    String.format("Spring bean '%s' not found in ApplicationContext for provider '%s'",
                            beanName, providerName));
        }
    }

    private <T extends NotificationProvider> T resolveByFqcn(
            String fqcn, Class<T> providerInterface, String providerName, Channel channel) {
        try {
            // Use ClassUtils from persistence-utils
            Class<?> clazz = ClassUtils.loadClass(fqcn);

            if (!providerInterface.isAssignableFrom(clazz)) {
                throw new ProviderConfigurationException(providerName, channel.name(),
                        String.format("Class '%s' does not implement %s", fqcn, providerInterface.getName()));
            }

            return instantiateClass(clazz, providerInterface, providerName, channel);

        } catch (IllegalArgumentException e) {
            throw new ProviderNotFoundException(
                    String.format("Class '%s' not found in classpath for provider '%s': %s",
                            fqcn, providerName, e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends NotificationProvider> T instantiateClass(
            Class<?> clazz, Class<T> providerInterface, String providerName, Channel channel) {
        try {
            // Try using ReflectionUtils from persistence-utils
            InitDTO initDTO = new InitDTO();
            initDTO.setFqcn(clazz.getName());

            // Bean supplier for any @Autowired dependencies
            Function<String, Object> beanSupplier = name -> {
                try {
                    return applicationContext.getBean(name);
                } catch (NoSuchBeanDefinitionException e) {
                    // Try by type
                    try {
                        return applicationContext.getBean(Class.forName(name));
                    } catch (Exception ex) {
                        throw new RuntimeException("Bean not found: " + name, ex);
                    }
                }
            };

            return ReflectionUtils.getInterfaceReference(initDTO, providerInterface, beanSupplier);

        } catch (Exception e) {
            // Fallback to simple no-arg constructor
            try {
                return providerInterface.cast(clazz.getDeclaredConstructor().newInstance());
            } catch (Exception ex) {
                throw new ProviderConfigurationException(providerName, channel.name(),
                        String.format("Failed to instantiate class '%s': %s", clazz.getName(), ex.getMessage()));
            }
        }
    }

    private String getAvailableBuiltIns(Channel channel, Map<String, Class<? extends NotificationProvider>> builtInProviders) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String key : builtInProviders.keySet()) {
            if (key.startsWith(channel.name() + ":")) {
                if (!first) sb.append(", ");
                sb.append(key.substring(channel.name().length() + 1));
                first = false;
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
