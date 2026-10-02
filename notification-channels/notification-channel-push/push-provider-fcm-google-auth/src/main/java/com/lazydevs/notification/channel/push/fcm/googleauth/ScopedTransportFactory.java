package com.lazydevs.notification.channel.push.fcm.googleauth;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.auth.http.HttpTransportFactory;

import java.io.IOException;

/**
 * The {@link HttpTransportFactory} handed to {@code GoogleCredentials.getApplicationDefault}.
 *
 * <p>The Google auth library resolves Application Default Credentials once per JVM and
 * caches the result together with the transport factory of the first caller, so a
 * per-tenant factory would route every later tenant through the first tenant's
 * transport. This factory is one JVM-wide instance instead, and its transport sends
 * each request through the {@link FcmHttpTransportAdapter} bound to {@link #CURRENT}
 * by the {@link GoogleAuthTokenProvider} that is fetching a token on this thread.
 * A request made while nothing is bound fails: no token traffic can bypass the
 * application's {@code FcmHttpTransport}.
 */
final class ScopedTransportFactory implements HttpTransportFactory {

    /** The adapter of the token call running on this thread. */
    static final ScopedValue<FcmHttpTransportAdapter> CURRENT = ScopedValue.newInstance();

    static final ScopedTransportFactory INSTANCE = new ScopedTransportFactory();

    private final HttpTransport router = new HttpTransport() {
        @Override
        public boolean supportsMethod(String method) {
            return true;
        }

        @Override
        protected LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
            if (!CURRENT.isBound()) {
                throw new IOException("Application Default Credentials made an HTTP call outside an FCM token"
                        + " request; refusing it, so that no call bypasses the FcmHttpTransport");
            }
            return CURRENT.get().buildRequest(method, url);
        }

        @Override
        public String toString() {
            return "ScopedTransportFactory.router";
        }
    };

    private ScopedTransportFactory() {
    }

    @Override
    public HttpTransport create() {
        return router;
    }
}
