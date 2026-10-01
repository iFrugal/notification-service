package com.lazydevs.notification.starter;

import com.lazydevs.notification.api.channel.EmailProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;

import java.util.Map;

/**
 * A custom email provider, public with a public no-arg constructor so the
 * {@code fqcn} resolution path can instantiate it.
 */
public class StubEmailProvider implements EmailProvider {

    private Map<String, Object> configuration = Map.of();

    @Override
    public String getProviderName() {
        return "stub";
    }

    @Override
    public void configure(Map<String, Object> properties) {
        this.configuration = Map.copyOf(properties);
    }

    @Override
    public SendResult send(NotificationRequest request, RenderedContent content) {
        throw new UnsupportedOperationException("test stub");
    }

    Map<String, Object> configuration() {
        return configuration;
    }
}
