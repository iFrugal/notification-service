package com.lazydevs.notification.channel.push.fcm.googleauth;

import com.lazydevs.notification.channel.push.fcm.FcmPushProviderAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Declares the {@link GoogleAuthTokenProviderFactory} as a bean with every
 * {@link FcmSubjectTokenSupplier} bean (DD-37), so a tenant can use
 * {@code credentials: external-account-supplier:<name>}. The FCM provider asks bean
 * factories before the {@code ServiceLoader} ones, and keeps one instance per class, so this
 * one replaces the supplier-less instance {@code ServiceLoader} creates.
 *
 * @since 1.2.3
 */
@AutoConfiguration(before = FcmPushProviderAutoConfiguration.class)
public class FcmGoogleAuthAutoConfiguration {

    /**
     * @param suppliers every subject token supplier bean, in order
     * @return the factory
     */
    @Bean
    @ConditionalOnMissingBean(GoogleAuthTokenProviderFactory.class)
    public GoogleAuthTokenProviderFactory googleAuthTokenProviderFactory(ObjectProvider<FcmSubjectTokenSupplier> suppliers) {
        return new GoogleAuthTokenProviderFactory(suppliers.orderedStream().toList());
    }
}
