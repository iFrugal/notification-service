package com.lazydevs.notification.channel.whatsapp.meta;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.boot.context.properties.bind.BindableRuntimeHintsRegistrar;

/**
 * GraalVM hints for this module: the provider class, which
 * {@code ProviderResolver} may instantiate by name ({@code fqcn}), and the
 * webhook properties binding. JSON goes through the Jackson tree model and
 * needs nothing.
 */
public class MetaWhatsAppRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(MetaWhatsAppProvider.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.ACCESS_DECLARED_FIELDS);
        BindableRuntimeHintsRegistrar.forTypes(MetaWebhookProperties.class).registerHints(hints, classLoader);
    }
}
