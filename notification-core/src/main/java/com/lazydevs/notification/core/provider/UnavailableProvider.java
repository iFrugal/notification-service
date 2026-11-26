package com.lazydevs.notification.core.provider;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Stand-in that {@link ProviderRegistry} returns when a lazily initialised
 * provider could not be built (DD-29). Its send fails with
 * {@link ProviderRegistry#PROVIDER_NOT_CONFIGURED} as a
 * {@link FailureType#TRANSIENT} failure, so the service records it like any
 * other provider failure: retry policy, dead-letter store and audit apply
 * unchanged.
 *
 * <p>The first send reports the build failure the lookup just saw. A later
 * send on the same instance, which is a retry of that request, asks the
 * registry to build the provider again and delegates to it when that works.
 * The instance is never cached and never initialised, so the registry never
 * destroys it.
 */
final class UnavailableProvider implements NotificationProvider {

    private final Channel channel;
    private final String providerName;
    private final String reason;
    private final Supplier<NotificationProvider> rebuild;
    private final AtomicBoolean reported = new AtomicBoolean();

    UnavailableProvider(Channel channel, String providerName, String reason,
                        Supplier<NotificationProvider> rebuild) {
        this.channel = channel;
        this.providerName = providerName;
        this.reason = reason;
        this.rebuild = rebuild;
    }

    @Override
    public Channel getChannel() {
        return channel;
    }

    @Override
    public String getProviderName() {
        return providerName;
    }

    @Override
    public void configure(Map<String, Object> properties) {
        // Never configured: the registry builds a real provider instead.
    }

    @Override
    public SendResult send(NotificationRequest request, RenderedContent content) {
        if (reported.compareAndSet(false, true)) {
            return failure();
        }
        NotificationProvider provider = rebuild.get();
        if (provider instanceof UnavailableProvider unavailable) {
            return unavailable.failure();
        }
        return provider.send(request, content);
    }

    @Override
    public boolean isHealthy() {
        return false;
    }

    SendResult failure() {
        return SendResult.failure(ProviderRegistry.PROVIDER_NOT_CONFIGURED, reason, FailureType.TRANSIENT);
    }
}
