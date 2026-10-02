package com.lazydevs.notification.channel.push.fcm;

import java.time.Clock;

/**
 * The built-in {@link FcmAccessTokenProviderFactory}: a service-account JSON key,
 * as a file path or inline JSON, through {@link ServiceAccountJwtTokenProvider}.
 *
 * <p>Supports every {@code credentials} value except {@code adc} and
 * {@code external-account:<path>}, which need
 * {@code com.github.ifrugal:push-provider-fcm-google-auth}.
 * Registered in {@code META-INF/services}, and also used when that entry is lost
 * (for example by a shading plugin that drops service files).
 *
 * @since 1.2.0
 */
public final class ServiceAccountJwtTokenProviderFactory implements FcmAccessTokenProviderFactory {

    @Override
    public boolean supports(String credentials) {
        return FcmSettings.hasText(credentials) && !FcmSettings.needsGoogleAuth(credentials.trim());
    }

    @Override
    public FcmAccessTokenProvider create(String credentials, FcmHttpTransport transport, Clock clock) {
        return ServiceAccountJwtTokenProvider.fromCredentials(credentials, transport, clock, null,
                FcmSettings.DEFAULT_TOKEN_REFRESH_MARGIN, FcmSettings.DEFAULT_TIMEOUT);
    }

    @Override
    public FcmAccessTokenProvider create(FcmSettings settings, FcmHttpTransport transport, Clock clock) {
        return ServiceAccountJwtTokenProvider.fromCredentials(settings.credentials(), transport, clock,
                settings.tokenEndpoint(), settings.tokenRefreshMargin(), settings.timeout());
    }
}
