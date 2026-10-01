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
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves notification providers from configuration.
 * Uses beanName for Spring beans, fqcn for reflection-based instantiation,
 * and otherwise the bean a built-in provider module registers under the
 * conventional name (see {@link BuiltInProviders#beanName(Channel, String)}).
 */
@Slf4j
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
     * 3. Otherwise the bean registered under the conventional built-in name,
     *    for example {@code smtpEmailProvider}; built-in modules register it
     *    as a prototype, so each call returns a fresh instance
     *
     * @param tenantId          the tenant whose configuration names the provider, used in error messages
     * @param providerName      the provider name from config
     * @param channel           the channel
     * @param config            the provider configuration
     * @param providerInterface the expected interface class
     * @return the resolved provider instance (not yet configured/initialized)
     * @throws ProviderNotFoundException when nothing provides the name, with a message
     *                                   naming the missing Maven artifact for a known built-in
     */
    public <T extends NotificationProvider> T resolve(
            String tenantId,
            String providerName,
            Channel channel,
            ProviderConfig config,
            Class<T> providerInterface) {

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

        // 3. Built-in provider, registered by its module under the conventional bean name
        String conventionalBeanName = BuiltInProviders.beanName(channel, providerName);
        if (applicationContext.containsBean(conventionalBeanName)) {
            log.debug("Resolving provider '{}' via built-in bean: {}", providerName, conventionalBeanName);
            return resolveByBeanName(conventionalBeanName, providerInterface, providerName, channel);
        }

        // 4. Not found: explain why
        String channelName = channel.name().toLowerCase(Locale.ROOT);
        Optional<BuiltInProviders.Entry> builtIn = BuiltInProviders.find(channel, providerName);
        if (builtIn.isPresent() && builtIn.get().implemented()) {
            throw new ProviderNotFoundException(String.format(
                    "Built-in provider '%s' for channel '%s' is not on the classpath. "
                            + "Add the Maven dependency com.github.ifrugal:%s (same version as notification-core), "
                            + "or set 'beanName' or 'fqcn' under notification.tenants.%s.channels.%s.providers.%s.",
                    providerName, channelName, builtIn.get().artifactId(), tenantId, channelName, providerName));
        }
        if (builtIn.isPresent()) {
            throw new ProviderNotFoundException(String.format(
                    "Built-in provider '%s' for channel '%s' is not implemented in this release. "
                            + "Set 'beanName' or 'fqcn' to a custom implementation.",
                    providerName, channelName));
        }
        throw new ProviderNotFoundException(
                String.format("Provider '%s' not found for channel '%s'. " +
                        "For external providers, specify 'beanName' (for Spring beans) or 'fqcn' (for class instantiation). " +
                        "Available built-in providers for %s: %s",
                        providerName, channel, channel, getAvailableBuiltIns(channel)));
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
        } catch (NoSuchBeanDefinitionException _) {
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
                } catch (NoSuchBeanDefinitionException _) {
                    // Try by type
                    try {
                        return applicationContext.getBean(Class.forName(name));
                    } catch (Exception ex) {
                        throw new ProviderConfigurationException(
                                "Bean not found while resolving @Autowired dependency '" + name + "'",
                                ex);
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

    /** The built-in names whose module is on the classpath, judged by their registered bean. */
    private String getAvailableBuiltIns(Channel channel) {
        return BuiltInProviders.forChannel(channel).stream()
                .filter(entry -> applicationContext.containsBean(entry.beanName()))
                .map(BuiltInProviders.Entry::name)
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
