package com.lazydevs.notification.api.delivery;

/**
 * Hands a {@link DeliveryEvent} that a provider learned about while sending
 * to the registered {@link DeliveryEventListener}s, the same listeners that
 * receive webhook callbacks (DD-16, DD-25).
 *
 * <p>Some providers learn the delivery outcome in the send response itself,
 * for example a push service that answers "this device token is no longer
 * registered". Such a provider implements {@link DeliveryEventEmitter}; the
 * provider registry gives it the publisher before configuring it.
 *
 * <p>Call {@link #publish(DeliveryEvent)} on the thread that runs the send:
 * listeners may read thread-bound state such as the current tenant.
 * Implementations must not throw; a failing listener must not fail the send.
 *
 * @since 1.2.0
 */
@FunctionalInterface
public interface DeliveryEventPublisher {

    /** A publisher that drops every event; the default until one is injected. */
    DeliveryEventPublisher NO_OP = event -> {
        // Nothing listens.
    };

    /**
     * Deliver {@code event} to every registered listener.
     *
     * @param event the event, never {@code null}
     */
    void publish(DeliveryEvent event);
}
