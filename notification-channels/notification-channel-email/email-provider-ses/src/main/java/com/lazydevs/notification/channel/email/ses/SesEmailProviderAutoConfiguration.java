package com.lazydevs.notification.channel.email.ses;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import java.util.Map;

/**
 * Registers the {@code sesEmailProvider} bean whenever this module and the
 * AWS SES v2 SDK are on the classpath.
 *
 * <p>The bean is a prototype: {@code ProviderRegistry} configures one instance
 * per tenant through {@link SesEmailProvider#configure(Map)}, so instances
 * must never be shared.
 * An application bean with the same name replaces it.
 */
@AutoConfiguration
@ConditionalOnClass(SesV2Client.class)
public class SesEmailProviderAutoConfiguration {

    /** Bean name the provider catalog refers to for {@code EMAIL:ses}. */
    public static final String BEAN_NAME = "sesEmailProvider";

    /** @return a fresh, unconfigured provider per lookup */
    @Bean(BEAN_NAME)
    @Scope(BeanDefinition.SCOPE_PROTOTYPE)
    @ConditionalOnMissingBean(name = BEAN_NAME)
    public SesEmailProvider sesEmailProvider() {
        return new SesEmailProvider();
    }
}
