package com.lazydevs.notification.channel.push.fcm.googleauth;

import com.lazydevs.notification.channel.push.fcm.FcmHttpRequest;
import com.lazydevs.notification.channel.push.fcm.FcmHttpResponse;
import com.lazydevs.notification.channel.push.fcm.FcmHttpTransport;
import com.lazydevs.notification.channel.push.fcm.FcmTransportException;
import com.lazydevs.notification.channel.push.fcm.JdkFcmHttpTransport;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The application's {@link FcmHttpTransport} in these tests: counts and records every
 * request, and sends a request for an {@code https://*.invalid} host (the hosts the
 * credential files name, which never resolve) to the loopback stub.
 * Any HTTP call that bypassed this transport would go to an {@code .invalid} host and fail.
 */
final class StubRoutingTransport implements FcmHttpTransport {

    private final URI stub;
    private final List<FcmHttpRequest> requests = new CopyOnWriteArrayList<>();

    StubRoutingTransport(GoogleStubServer stub) {
        this.stub = stub.baseUri();
    }

    @Override
    public FcmHttpResponse execute(FcmHttpRequest request) throws FcmTransportException {
        requests.add(request);
        URI uri = request.uri();
        if (uri.getHost() != null && uri.getHost().endsWith(".invalid")) {
            uri = stub.resolve(uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        }
        return JdkFcmHttpTransport.shared().execute(new FcmHttpRequest(request.method(), uri, request.headers(),
                request.body(), request.timeout()));
    }

    int count() {
        return requests.size();
    }

    List<FcmHttpRequest> requests() {
        return List.copyOf(requests);
    }

    /** @return the paths requested, in order */
    List<String> paths() {
        return requests.stream().map(r -> r.uri().getPath()).toList();
    }
}
