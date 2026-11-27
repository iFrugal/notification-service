package com.lazydevs.notification.channel.push.fcm;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

import java.util.List;

/**
 * GraalVM native image hints for the FCM provider (DD-37).
 *
 * <p>The provider finds its {@link FcmAccessTokenProviderFactory} implementations with
 * {@link java.util.ServiceLoader}, so the service file must be in the image and every
 * implementation must be constructible reflectively. The google-auth adapter's factory is
 * registered by name and costs nothing when that module is absent. The FCM JSON is built and
 * read as Jackson trees, so no DTO needs a binding hint.
 *
 * @since 1.2.3
 */
public class FcmRuntimeHints implements RuntimeHintsRegistrar {

    /** The {@code META-INF/services} file the provider reads. */
    static final String SERVICE_FILE = "META-INF/services/" + FcmAccessTokenProviderFactory.class.getName();

    /** Every {@link FcmAccessTokenProviderFactory} the library ships. */
    static final List<String> FACTORIES = List.of(
            ServiceAccountJwtTokenProviderFactory.class.getName(),
            "com.lazydevs.notification.channel.push.fcm.googleauth.GoogleAuthTokenProviderFactory");

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern(SERVICE_FILE);
        FACTORIES.forEach(factory -> hints.reflection().registerType(TypeReference.of(factory),
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS));
    }
}
