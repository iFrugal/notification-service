package com.lazydevs.notification.api.delivery;

import java.util.List;
import java.util.Optional;

/**
 * Persistent store for {@link DeliveryEvent}s ingested via the DD-16
 * webhook surface (DD-17).
 *
 * <p>The store <strong>is also a {@link DeliveryEventListener}</strong>
 * — the default {@link #onEvent(DeliveryEvent)} method bridges to
 * {@link #add(DeliveryEvent)}, so registering a store as a Spring
 * {@code @Component} is enough to wire it into the listener fan-out
 * the webhook controller already injects via
 * {@code List<DeliveryEventListener>}. No autoconfig glue needed.
 *
 * <p>Composes with custom listeners — operators with their own audit
 * pipeline register it alongside the default store, and both receive
 * every event in registration order.
 *
 * <p>Default implementation is {@code InMemoryDeliveryEventStore}
 * (Caffeine bounded LRU). A Redis-backed implementation lives in
 * {@code notification-redis} for multi-pod deployments.
 */
public interface DeliveryEventStore extends DeliveryEventListener {

    /**
     * Persist a single event. Implementations <strong>must never
     * throw</strong> — losing a delivery event is regrettable but a
     * flaky store mustn't make the webhook handler return 5xx (the
     * provider would then retry forever, amplifying load). Log and
     * move on.
     */
    void add(DeliveryEvent event);

    /**
     * Bridge to the {@link DeliveryEventListener} seam: the webhook
     * controller dispatches to listeners; this default makes "be a
     * listener" the trivial case of "be a store." Implementations
     * that need a different semantic — e.g. dedup at the listener
     * level — can override.
     */
    @Override
    default void onEvent(DeliveryEvent event) {
        add(event);
    }

    /**
     * Most-recent-first snapshot of currently-held events.
     *
     * <p>Returns {@link Optional#empty()} when the backend can't
     * produce a snapshot cheaply — same shape as
     * {@code DeadLetterStore.snapshot()}. The in-memory and Redis
     * defaults both return a present list.
     */
    Optional<List<DeliveryEvent>> snapshot();

    /**
     * Look up the most recent events for a single notification's
     * {@code providerMessageId}. The join key matches
     * {@link com.lazydevs.notification.api.model.NotificationResponse#providerMessageId()}
     * set when the original send succeeded.
     *
     * <p>{@code providerName} is part of the key because
     * {@code providerMessageId}s aren't guaranteed unique across
     * providers — a Twilio SMS and a SES email could in principle
     * carry the same id string. Scoping by provider prevents the
     * cross-provider collision.
     *
     * <p>Returns {@link Optional#empty()} for backends that can't
     * answer the lookup cheaply.
     */
    Optional<List<DeliveryEvent>> findByProviderMessageId(
            String providerName, String providerMessageId);

    /**
     * Number of events currently held. Returns {@code -1} when the
     * backend can't answer cheaply (mirrors {@link #snapshot()}).
     */
    int size();
}
