package com.lazydevs.notification.core.config;

import com.lazydevs.notification.api.template.TemplateChangeFeed;
import com.lazydevs.notification.api.template.TemplateChangeListener;
import com.lazydevs.notification.api.template.TemplateSource;
import com.lazydevs.notification.api.template.TemplateStore;
import com.lazydevs.notification.core.template.ChannelContentRenderer;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import com.lazydevs.notification.core.template.TemplateChangeDispatcher;
import com.lazydevs.notification.core.template.admin.TemplateAdminService;
import com.lazydevs.notification.core.template.admin.TemplateSampleProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires a {@link TemplateStore} into the rest of the library (DD-38): change propagation
 * and the {@link TemplateAdminService}. Active only when a store bean exists, from
 * {@code notification.template.store.type=memory|jdbc} or declared by the application.
 * <p>
 * Every {@link TemplateChangeListener} bean (the engine among them) hears about a publish in
 * this process at once, and about changes elsewhere through each {@link TemplateChangeFeed}.
 */
@AutoConfiguration(after = {NotificationCoreAutoConfiguration.class, NotificationCoreDefaultsAutoConfiguration.class},
        afterName = "com.lazydevs.notification.store.jdbc.JdbcTemplateStoreAutoConfiguration")
@ConditionalOnBean(TemplateStore.class)
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationTemplateStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TemplateChangeDispatcher templateChangeDispatcher(ObjectProvider<TemplateChangeListener> listeners,
                                                             ObjectProvider<TemplateChangeFeed> feeds) {
        TemplateChangeDispatcher dispatcher = new TemplateChangeDispatcher(listeners::orderedStream);
        feeds.orderedStream().forEach(dispatcher::listenTo);
        return dispatcher;
    }

    @Bean
    @ConditionalOnMissingBean
    public TemplateAdminService templateAdminService(NotificationProperties properties,
                                                     NotificationTemplateEngine engine,
                                                     TemplateSource templateSource,
                                                     TemplateStore templateStore,
                                                     ObjectProvider<TemplateSampleProvider> samples,
                                                     ObjectProvider<ChannelContentRenderer> renderers,
                                                     TemplateChangeDispatcher dispatcher) {
        NotificationProperties.TemplateStoreProperties store = properties.getTemplate().getStore();
        TemplateAdminService.Options options = new TemplateAdminService.Options(store.getEditableChannels(),
                Math.toIntExact(store.getMaxContentSize().toBytes()), TemplateAdminService.DEFAULT_EVENT_LIMIT,
                renderers.orderedStream().toList());
        return new TemplateAdminService(engine, templateSource, templateStore, options,
                samples.getIfAvailable(() -> TemplateSampleProvider.NONE), dispatcher::templateChanged);
    }
}
