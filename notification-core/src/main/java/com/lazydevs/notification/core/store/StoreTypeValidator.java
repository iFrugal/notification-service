package com.lazydevs.notification.core.store;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Fails startup when an enabled feature resolves to a store family whose
 * module is not on the classpath.
 *
 * <p>Without this check a missing module is silent: the family's
 * auto-configuration never runs, so the in-memory default registers
 * instead and a multi-pod deployment quietly loses shared state.
 * Runs as a {@link BeanFactoryPostProcessor}, so it fires once the
 * configuration is known but before any bean is created.
 *
 * <p>Also warns once about {@code notification.redis.enabled}, which was
 * never read and is no longer bound.
 */
@Slf4j
public class StoreTypeValidator implements BeanFactoryPostProcessor, EnvironmentAware, BeanClassLoaderAware {

    static final String LEGACY_REDIS_ENABLED_PROPERTY = "notification.redis.enabled";

    private Environment environment;
    private ClassLoader classLoader = ClassUtils.getDefaultClassLoader();

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        Binder binder = Binder.get(environment);
        if (binder.bind(LEGACY_REDIS_ENABLED_PROPERTY, String.class).isBound()) {
            log.warn("notification.redis.enabled is no longer read; use notification.store.type=redis");
        }
        for (StoreFeature feature : StoreFeature.values()) {
            if (feature.isEnabled(binder)) {
                check(feature, feature.resolve(binder).type());
            }
        }
    }

    private void check(StoreFeature feature, StoreType type) {
        if (!type.requiresModule() || ClassUtils.isPresent(type.markerClassName(), classLoader)) {
            return;
        }
        throw new InvalidConfigurationPropertyValueException(StoreFeature.STORE_TYPE_PROPERTY, type.id(),
                "Store family '" + type.id() + "' is selected for " + feature.id() + ", but "
                        + type.artifactId() + " is not on the classpath. Add the Maven dependency com.github.ifrugal:"
                        + type.artifactId() + " (same version as notification-core).");
    }
}
