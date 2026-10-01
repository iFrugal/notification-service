package com.lazydevs.notification.channel.email.smtp;

import jakarta.mail.Session;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

import java.util.Map;

/**
 * Registers the {@code smtpEmailProvider} bean whenever this module and
 * Jakarta Mail are on the classpath.
 *
 * <p>The bean is a prototype: {@code ProviderRegistry} configures one instance
 * per tenant through {@link SmtpEmailProvider#configure(Map)}, so instances
 * must never be shared.
 * An application bean with the same name replaces it.
 */
@AutoConfiguration
@ConditionalOnClass(Session.class)
public class SmtpEmailProviderAutoConfiguration {

    /** Bean name the provider catalog refers to for {@code EMAIL:smtp}. */
    public static final String BEAN_NAME = "smtpEmailProvider";

    /** @return a fresh, unconfigured provider per lookup */
    @Bean(BEAN_NAME)
    @Scope(BeanDefinition.SCOPE_PROTOTYPE)
    @ConditionalOnMissingBean(name = BEAN_NAME)
    public SmtpEmailProvider smtpEmailProvider() {
        return new SmtpEmailProvider();
    }
}
