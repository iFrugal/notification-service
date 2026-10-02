package com.lazydevs.notification.channel.push.fcm.googleauth;

import com.google.api.client.http.HttpTransport;
import com.google.auth.http.HttpTransportFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registered in the test {@code META-INF/services/com.google.auth.http.HttpTransportFactory}:
 * the factory the Google auth library falls back to when it is given none. The adapter
 * always passes its own, so {@link #created()} must stay zero; a transport created here
 * refuses every request.
 */
public final class CountingHttpTransportFactory implements HttpTransportFactory {

    private static final AtomicInteger CREATED = new AtomicInteger();

    static int created() {
        return CREATED.get();
    }

    @Override
    public HttpTransport create() {
        CREATED.incrementAndGet();
        throw new IllegalStateException("the Google auth library used its fallback HttpTransportFactory");
    }
}
