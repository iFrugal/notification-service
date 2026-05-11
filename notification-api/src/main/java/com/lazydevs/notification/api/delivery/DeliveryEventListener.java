package com.lazydevs.notification.api.delivery;

/**
 * SPI for handling parsed, signature-verified delivery callbacks
 * (DD-16). Operators register their own listener to fan events out to
 * an audit pipeline, metrics system, or in-process state.
 *
 * <p>Multiple listeners can be registered; the webhook controller
 * dispatches to all of them in registration order. A failing listener
 * does not short-circuit the others — webhook handlers always return
 * {@code 200} once the signature passed and the event was parsed,
 * regardless of downstream listener behaviour. (Returning anything else
 * makes providers retry, which we don't want when our own bug is the
 * problem.)
 *
 * <p>Default implementation is {@code LoggingDeliveryEventListener}
 * which logs at INFO. Suitable as a "wire it up, prove the signature
 * path" baseline; operators replace it with one that persists.
 *
 * <p>Idempotency is the listener's responsibility. Providers retry
 * callbacks; the same logical event may arrive 2-N times. Listeners
 * that persist should use {@link DeliveryEvent#providerEventId()} as
 * the dedup key.
 */
@FunctionalInterface
public interface DeliveryEventListener {

    /**
     * Notify the listener of a delivery event. Implementations must
     * not throw — failure to handle the event must not propagate to
     * the webhook endpoint.
     *
     * <p>If you must signal a failure, log it and move on. The webhook
     * controller's contract with the provider is "200 means we
     * received it"; "we received it but couldn't store it" is a
     * service-internal problem, not a provider problem.
     */
    void onEvent(DeliveryEvent event);
}
