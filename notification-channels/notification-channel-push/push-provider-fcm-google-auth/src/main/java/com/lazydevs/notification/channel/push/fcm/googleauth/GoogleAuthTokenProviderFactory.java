package com.lazydevs.notification.channel.push.fcm.googleauth;

import com.lazydevs.notification.channel.push.fcm.FcmAccessTokenProvider;
import com.lazydevs.notification.channel.push.fcm.FcmAccessTokenProviderFactory;
import com.lazydevs.notification.channel.push.fcm.FcmHttpTransport;
import com.lazydevs.notification.channel.push.fcm.FcmSettings;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link FcmAccessTokenProviderFactory} for {@code credentials: adc},
 * {@code credentials: external-account:<path>} and (since 1.2.3)
 * {@code credentials: external-account-supplier:<name>}, registered in
 * {@code META-INF/services}: putting this module on the classpath is all it takes.
 *
 * <p>{@code external-account-supplier:<name>} needs the {@link FcmSubjectTokenSupplier} of that
 * name. In a Spring application {@link FcmGoogleAuthAutoConfiguration} declares this factory
 * as a bean with every supplier bean, and the FCM provider asks bean factories before the
 * {@code ServiceLoader} ones; without Spring, construct it with the suppliers and pass it to
 * the provider.
 *
 * <p>Both create a {@link GoogleAuthTokenProvider} whose token traffic goes through the
 * tenant's {@link FcmHttpTransport}. The tenant's {@code timeout} bounds each HTTP call and
 * {@code token-refresh-margin} decides when the token is refreshed; {@code token-endpoint}
 * does not apply, because the credential configuration names its own endpoints.
 *
 * @since 1.2.0
 */
@Slf4j
public final class GoogleAuthTokenProviderFactory implements FcmAccessTokenProviderFactory {

    private final Map<String, FcmSubjectTokenSupplier> suppliers;

    /** Without subject token suppliers, as {@code ServiceLoader} creates it. */
    public GoogleAuthTokenProviderFactory() {
        this(List.of());
    }

    /**
     * @param suppliers the subject token suppliers {@code external-account-supplier:<name>} may name
     * @throws IllegalStateException when two suppliers share a name or one has none
     * @since 1.2.3
     */
    public GoogleAuthTokenProviderFactory(List<FcmSubjectTokenSupplier> suppliers) {
        Map<String, FcmSubjectTokenSupplier> byName = new LinkedHashMap<>();
        for (FcmSubjectTokenSupplier supplier : suppliers == null ? List.<FcmSubjectTokenSupplier>of() : suppliers) {
            String name = supplier.name() == null ? "" : supplier.name().strip();
            if (name.isEmpty()) {
                throw new IllegalStateException("FcmSubjectTokenSupplier " + supplier.getClass().getName()
                        + " has no name");
            }
            FcmSubjectTokenSupplier existing = byName.putIfAbsent(name, supplier);
            if (existing != null) {
                throw new IllegalStateException("Two FcmSubjectTokenSupplier beans are named '" + name + "': "
                        + existing.getClass().getName() + " and " + supplier.getClass().getName());
            }
        }
        this.suppliers = Map.copyOf(byName);
    }

    @Override
    public boolean supports(String credentials) {
        if (credentials == null) {
            return false;
        }
        String spec = credentials.trim();
        return FcmSettings.CREDENTIALS_ADC.equalsIgnoreCase(spec) || isExternalAccount(spec)
                || FcmSettings.isExternalAccountSupplier(spec);
    }

    @Override
    public FcmAccessTokenProvider create(String credentials, FcmHttpTransport transport, Clock clock) {
        return create(credentials, transport, clock, FcmSettings.DEFAULT_TOKEN_REFRESH_MARGIN,
                FcmSettings.DEFAULT_TIMEOUT);
    }

    @Override
    public FcmAccessTokenProvider create(FcmSettings settings, FcmHttpTransport transport, Clock clock) {
        if (settings.tokenEndpoint() != null) {
            log.warn("FCM push provider: '{}' is ignored for credentials {}; the credential configuration names"
                    + " its own token endpoints", FcmSettings.TOKEN_ENDPOINT,
                    FcmSettings.describeCredentials(settings.credentials()));
        }
        return create(settings.credentials(), transport, clock, settings.tokenRefreshMargin(), settings.timeout());
    }

    private FcmAccessTokenProvider create(String credentials, FcmHttpTransport transport, Clock clock,
                                          Duration refreshMargin, Duration timeout) {
        String spec = credentials == null ? "" : credentials.trim();
        if (FcmSettings.CREDENTIALS_ADC.equalsIgnoreCase(spec)) {
            return GoogleAuthTokenProvider.applicationDefault(transport, clock, refreshMargin, timeout);
        }
        if (FcmSettings.isExternalAccountSupplier(spec)) {
            return GoogleAuthTokenProvider.externalAccountSupplier(supplier(spec), transport, clock, refreshMargin,
                    timeout);
        }
        if (!isExternalAccount(spec)) {
            throw GoogleAuthTokenProvider.invalid("credentials " + FcmSettings.describeCredentials(credentials)
                    + " are not '" + FcmSettings.CREDENTIALS_ADC + "', '"
                    + FcmSettings.CREDENTIALS_EXTERNAL_ACCOUNT + "<path>' or '"
                    + FcmSettings.CREDENTIALS_EXTERNAL_ACCOUNT_SUPPLIER + "<name>'");
        }
        String location = spec.substring(FcmSettings.CREDENTIALS_EXTERNAL_ACCOUNT.length()).trim();
        if (location.isEmpty()) {
            throw GoogleAuthTokenProvider.invalid("'" + FcmSettings.CREDENTIALS_EXTERNAL_ACCOUNT
                    + "' needs the path of an external account credential configuration file");
        }
        Path config;
        try {
            config = Path.of(location);
        } catch (InvalidPathException e) {
            throw GoogleAuthTokenProvider.invalid("'" + location + "' is not a valid path: " + e.getReason());
        }
        return GoogleAuthTokenProvider.externalAccount(config, transport, clock, refreshMargin, timeout);
    }

    private FcmSubjectTokenSupplier supplier(String spec) {
        String name = spec.substring(FcmSettings.CREDENTIALS_EXTERNAL_ACCOUNT_SUPPLIER.length()).strip();
        if (name.isEmpty()) {
            throw GoogleAuthTokenProvider.invalid("'" + FcmSettings.CREDENTIALS_EXTERNAL_ACCOUNT_SUPPLIER
                    + "' needs the name of an FcmSubjectTokenSupplier bean");
        }
        FcmSubjectTokenSupplier supplier = suppliers.get(name);
        if (supplier == null) {
            throw GoogleAuthTokenProvider.invalid("no FcmSubjectTokenSupplier is named '" + name + "'"
                    + (suppliers.isEmpty() ? "; declare one as a Spring bean" : "; known: " + suppliers.keySet()));
        }
        return supplier;
    }

    private static boolean isExternalAccount(String spec) {
        String prefix = FcmSettings.CREDENTIALS_EXTERNAL_ACCOUNT;
        return spec.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
