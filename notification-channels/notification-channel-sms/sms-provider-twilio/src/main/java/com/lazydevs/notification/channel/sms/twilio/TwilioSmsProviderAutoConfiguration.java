package com.lazydevs.notification.channel.sms.twilio;

import com.twilio.Twilio;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

import java.util.Map;

/**
 * Registers the {@code twilioSmsProvider} bean whenever this module and the
 * Twilio SDK are on the classpath.
 *
 * <p>The bean is a prototype: {@code ProviderRegistry} configures one instance
 * per tenant through {@link TwilioSmsProvider#configure(Map)}, so instances
 * must never be shared.
 * An application bean with the same name replaces it.
 */
@AutoConfiguration
@ConditionalOnClass(Twilio.class)
public class TwilioSmsProviderAutoConfiguration {

    /** Bean name the provider catalog refers to for {@code SMS:twilio}. */
    public static final String BEAN_NAME = "twilioSmsProvider";

    /** @return a fresh, unconfigured provider per lookup */
    @Bean(BEAN_NAME)
    @Scope(BeanDefinition.SCOPE_PROTOTYPE)
    @ConditionalOnMissingBean(name = BEAN_NAME)
    public TwilioSmsProvider twilioSmsProvider() {
        return new TwilioSmsProvider();
    }
}
