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

/**
 * The {@link FcmAccessTokenProviderFactory} for {@code credentials: adc} and
 * {@code credentials: external-account:<path>}, registered in
 * {@code META-INF/services}: putting this module on the classpath is all it takes.
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

    @Override
    public boolean supports(String credentials) {
        if (credentials == null) {
            return false;
        }
        String spec = credentials.trim();
        return FcmSettings.CREDENTIALS_ADC.equalsIgnoreCase(spec) || isExternalAccount(spec);
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
        if (!isExternalAccount(spec)) {
            throw GoogleAuthTokenProvider.invalid("credentials " + FcmSettings.describeCredentials(credentials)
                    + " are not '" + FcmSettings.CREDENTIALS_ADC + "' or '"
                    + FcmSettings.CREDENTIALS_EXTERNAL_ACCOUNT + "<path>'");
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

    private static boolean isExternalAccount(String spec) {
        String prefix = FcmSettings.CREDENTIALS_EXTERNAL_ACCOUNT;
        return spec.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
