package com.lazydevs.notification.channel.whatsapp;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.boot.context.properties.bind.BindableRuntimeHintsRegistrar;

/**
 * GraalVM hints for binding {@link WhatsAppProperties} in a native image.
 * Nothing else in this module is reflective.
 */
public class WhatsAppRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        BindableRuntimeHintsRegistrar.forTypes(WhatsAppProperties.class).registerHints(hints, classLoader);
    }
}
