package com.lazydevs.notification.api.delivery;

/**
 * Implemented by a provider that publishes {@link DeliveryEvent}s it learns
 * about while sending (DD-25).
 *
 * <p>The provider registry calls {@link #setDeliveryEventPublisher} once per
 * provider instance, before {@code configure(Map)}, whichever way the
 * provider was resolved (bean name, class name or built-in name). A provider
 * used outside the registry keeps whatever it was given, so it should start
 * with {@link DeliveryEventPublisher#NO_OP}.
 *
 * @since 1.2.0
 */
public interface DeliveryEventEmitter {

    /**
     * Receive the publisher to hand events to.
     *
     * @param publisher the publisher, never {@code null}
     */
    void setDeliveryEventPublisher(DeliveryEventPublisher publisher);
}
