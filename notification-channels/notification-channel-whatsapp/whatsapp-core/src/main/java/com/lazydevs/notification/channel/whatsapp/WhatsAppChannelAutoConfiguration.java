package com.lazydevs.notification.channel.whatsapp;

import com.lazydevs.notification.api.template.TemplateStore;
import com.lazydevs.notification.core.config.NotificationCoreAutoConfiguration;
import com.lazydevs.notification.core.config.NotificationCoreDefaultsAutoConfiguration;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

import java.time.Duration;

/**
 * Registers the WhatsApp channel pieces whenever this module is on the
 * classpath (DD-35): the YAML {@link WhatsAppTemplateRegistry} and the
 * {@code WHATSAPP} {@link WhatsAppContentRenderer}.
 *
 * <p>Inert until configured: without {@code notification.whatsapp.templates}
 * and without a template name on the recipient, the renderer hands the request
 * to the FreeMarker template engine, as the pipeline did before 1.2.2.
 * Replace the registry by declaring a {@link WhatsAppTemplateRegistry} bean,
 * and the renderer by declaring a bean named {@value #RENDERER_BEAN_NAME}.
 * With a template store (DD-38) the registry is a
 * {@link StoreBackedWhatsAppTemplateRegistry} over the YAML one.
 */
@AutoConfiguration(after = {NotificationCoreAutoConfiguration.class, NotificationCoreDefaultsAutoConfiguration.class},
        afterName = "com.lazydevs.notification.store.jdbc.JdbcTemplateStoreAutoConfiguration")
@EnableConfigurationProperties(WhatsAppProperties.class)
@ImportRuntimeHints(WhatsAppRuntimeHints.class)
public class WhatsAppChannelAutoConfiguration {

    /** Bean name of the {@code WHATSAPP} content renderer. */
    public static final String RENDERER_BEAN_NAME = "whatsAppContentRenderer";

    /**
     * With a template store: bindings published in the store first, then the YAML ones.
     * Declared before the YAML registry so that one backs off.
     */
    @Bean
    @ConditionalOnBean(TemplateStore.class)
    @ConditionalOnMissingBean(WhatsAppTemplateRegistry.class)
    public StoreBackedWhatsAppTemplateRegistry storeBackedWhatsAppTemplateRegistry(
            WhatsAppProperties properties, TemplateStore templateStore, NotificationProperties notification) {
        NotificationProperties.TemplateProperties template = notification.getTemplate();
        return new StoreBackedWhatsAppTemplateRegistry(templateStore,
                new PropertiesWhatsAppTemplateRegistry(properties),
                Duration.ofSeconds(template.getCacheTtlSeconds()), template.getCacheMaxSize());
    }

    @Bean
    @ConditionalOnMissingBean(WhatsAppTemplateRegistry.class)
    public PropertiesWhatsAppTemplateRegistry whatsAppTemplateRegistry(WhatsAppProperties properties) {
        return new PropertiesWhatsAppTemplateRegistry(properties);
    }

    @Bean(RENDERER_BEAN_NAME)
    @ConditionalOnMissingBean(name = RENDERER_BEAN_NAME)
    public WhatsAppContentRenderer whatsAppContentRenderer(WhatsAppTemplateRegistry registry,
                                                           ObjectProvider<NotificationTemplateEngine> templateEngine) {
        NotificationTemplateEngine engine = templateEngine.getIfAvailable();
        return new WhatsAppContentRenderer(registry, engine == null ? null : engine::render);
    }
}
