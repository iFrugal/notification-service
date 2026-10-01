package com.lazydevs.notification.core.provider;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * GraalVM reflection hints for the provider classes notification-core may
 * instantiate.
 *
 * <p>Built-in providers are normally created by their module's
 * auto-configuration, which AOT compiles to a plain constructor call; these
 * hints keep the {@code fqcn} path working for the same classes in a native
 * image.
 * Types are registered by name, so a class whose module is absent costs
 * nothing.
 * Classes named by {@code fqcn} in the application's own configuration are
 * covered at build time by {@link ProviderFqcnAotProcessor}.
 */
public class ProviderRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        BuiltInProviders.all().forEach(entry -> registerProviderType(hints, entry.className()));
    }

    /** Registers what reflective provider instantiation needs: constructors and field injection. */
    static void registerProviderType(RuntimeHints hints, String className) {
        hints.reflection().registerType(TypeReference.of(className),
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.ACCESS_DECLARED_FIELDS);
    }
}
