package com.lazydevs.notification.channel.whatsapp.meta;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.Scope;

import java.util.Map;

/**
 * Registers the Meta WhatsApp pieces (DD-35).
 *
 * <ul>
 *   <li>The prototype bean {@value #BEAN_NAME}, whenever this module is on the
 *       classpath: inert until a tenant configures {@code WHATSAPP:meta}, because
 *       {@code ProviderRegistry} configures one instance per tenant through
 *       {@link MetaWhatsAppProvider#configure(Map)}. A {@link MetaHttpTransport}
 *       bean replaces {@link JdkMetaHttpTransport#shared()} and a
 *       {@link MetaCredentialsProvider} bean replaces the static credentials,
 *       each when there is exactly one.</li>
 *   <li>The {@link MetaWebhookHandler} bean {@value #WEBHOOK_BEAN_NAME}, only with
 *       {@code notification.webhooks.meta.enabled=true}.</li>
 * </ul>
 */
@AutoConfiguration
@ImportRuntimeHints(MetaWhatsAppRuntimeHints.class)
public class MetaWhatsAppProviderAutoConfiguration {

    /** Bean name the provider catalog refers to for {@code WHATSAPP:meta}. */
    public static final String BEAN_NAME = "metaWhatsappProvider";

    /** Bean name of the webhook handler. */
    public static final String WEBHOOK_BEAN_NAME = "metaWebhookHandler";

    /**
     * A fresh, unconfigured provider per lookup.
     *
     * @param transport   the application's {@link MetaHttpTransport}, if it has exactly one
     * @param credentials the application's {@link MetaCredentialsProvider}, if it has exactly one
     * @return a new provider instance
     */
    @Bean(BEAN_NAME)
    @Scope(BeanDefinition.SCOPE_PROTOTYPE)
    @ConditionalOnMissingBean(name = BEAN_NAME)
    public MetaWhatsAppProvider metaWhatsappProvider(ObjectProvider<MetaHttpTransport> transport,
                                                     ObjectProvider<MetaCredentialsProvider> credentials) {
        return new MetaWhatsAppProvider(transport.getIfUnique(JdkMetaHttpTransport::shared),
                credentials.getIfUnique());
    }

    /**
     * The status webhook, opt-in.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty(MetaWebhookProperties.PREFIX + ".enabled")
    @EnableConfigurationProperties(MetaWebhookProperties.class)
    static class WebhookConfiguration {

        @Bean(WEBHOOK_BEAN_NAME)
        @ConditionalOnMissingBean(name = WEBHOOK_BEAN_NAME)
        MetaWebhookHandler metaWebhookHandler(MetaWebhookProperties properties) {
            return new MetaWebhookHandler(properties);
        }
    }
}
