package com.lazydevs.notification.starter;

import com.lazydevs.notification.api.channel.EmailProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventEmitter;
import com.lazydevs.notification.api.delivery.DeliveryEventPublisher;
import com.lazydevs.notification.api.delivery.DeliveryEvents;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An email provider that publishes a delivery event while sending, the way a
 * push provider reports an unregistered token. Public with a public no-arg
 * constructor so the {@code fqcn} resolution path can instantiate it.
 *
 * <p>Each send publishes a {@link DeliveryStatus#BOUNCED} invalid-target event
 * and fails as {@link FailureType#AMBIGUOUS} with message id
 * {@link #MESSAGE_ID}.
 */
public class EmittingStubEmailProvider implements EmailProvider, DeliveryEventEmitter {

    static final String MESSAGE_ID = "stub-msg-1";

    private DeliveryEventPublisher publisher = DeliveryEventPublisher.NO_OP;
    private boolean publisherSetBeforeConfigure;
    private boolean configured;
    private final AtomicInteger sends = new AtomicInteger();

    @Override
    public String getProviderName() {
        return "emitting-stub";
    }

    @Override
    public void setDeliveryEventPublisher(DeliveryEventPublisher publisher) {
        this.publisher = publisher;
        this.publisherSetBeforeConfigure = !configured;
    }

    @Override
    public void configure(Map<String, Object> properties) {
        configured = true;
    }

    @Override
    public SendResult send(NotificationRequest request, RenderedContent content) {
        sends.incrementAndGet();
        publisher.publish(new DeliveryEvent(Instant.now(), getProviderName(), MESSAGE_ID,
                "evt-" + request.getRequestId(), DeliveryStatus.BOUNCED, DeliveryEvents.REASON_INVALID_TARGET,
                Map.of(DeliveryEvents.ATTR_TARGET_TYPE, DeliveryEvents.TARGET_TYPE_TOKEN,
                        DeliveryEvents.ATTR_TOKEN_HASH, "sha256:0123456789abcdef")));
        return SendResult.failure("STUB_READ_TIMEOUT", "read timed out", FailureType.AMBIGUOUS, MESSAGE_ID);
    }

    DeliveryEventPublisher publisher() {
        return publisher;
    }

    boolean publisherSetBeforeConfigure() {
        return publisherSetBeforeConfigure;
    }

    int sends() {
        return sends.get();
    }
}
