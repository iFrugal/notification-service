package com.lazydevs.notification.channel.push.fcm;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventPublisher;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.PushRecipient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Builders shared by the FCM tests. */
final class FcmTestSupport {

    /** A realistic-length registration token; never appears in logs or results. */
    static final String TOKEN = "fGx1-raw-registration-token:APA91bHun4MxP5egoKMwt2KZFBaFUH-1RYqx";
    static final String TOKEN_2 = "dQw4-raw-registration-token:APA91bE2nd2nd2nd2nd2nd2nd2nd2nd-2two";
    static final String TOKEN_3 = "zZz9-raw-registration-token:APA91bThirdThirdThirdThirdThird-3three";
    static final String FID = "cR4wInstallationIdRawValue0123456789";

    private FcmTestSupport() {
    }

    static PushRecipient token(String token) {
        return new PushRecipient(null, token, null, null, "Title", "Body", null, null, null, null, null);
    }

    static PushRecipient tokens(String... tokens) {
        return new PushRecipient(null, null, null, null, "Title", "Body", null, null, null, null, null, null,
                List.of(tokens));
    }

    static PushRecipient fid(String fid) {
        return new PushRecipient(null, null, null, null, "Title", "Body", null, null, null, null, null, fid, null);
    }

    static PushRecipient topic(String topic) {
        return new PushRecipient(null, null, topic, null, "Title", "Body", null, null, null, null, null);
    }

    static PushRecipient condition(String condition) {
        return new PushRecipient(null, null, null, condition, "Title", "Body", null, null, null, null, null);
    }

    static NotificationRequest request(PushRecipient recipient) {
        return request(recipient, null);
    }

    static NotificationRequest request(PushRecipient recipient, Map<String, String> metadata) {
        return NotificationRequest.builder()
                .requestId("req-1")
                .tenantId("acme")
                .notificationType("ORDER_SHIPPED")
                .channel(Channel.PUSH)
                .recipient(recipient)
                .metadata(metadata)
                .build();
    }

    /** A publisher that records the events and the tenant bound to the publishing thread. */
    static final class RecordingPublisher implements DeliveryEventPublisher {
        final List<DeliveryEvent> events = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<String> tenants = new CopyOnWriteArrayList<>();

        @Override
        public void publish(DeliveryEvent event) {
            events.add(event);
            threads.add(Thread.currentThread().getName());
            tenants.add(String.valueOf(lazydevs.persistence.connection.multitenant.TenantContext.getTenantId()));
        }
    }

    /** A clock tests move by hand. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** A provider configured through configure/init against the stub, with extra properties. */
    static FcmPushProvider configured(FcmStubServer stub, Map<String, Object> extra) {
        return configured(stub, extra, Clock.systemUTC());
    }

    static FcmPushProvider configured(FcmStubServer stub, Map<String, Object> extra, Clock clock) {
        FcmPushProvider provider = new FcmPushProvider(JdkFcmHttpTransport.shared(), Map.of(), List.of(),
                FcmPushProvider.class.getClassLoader(), clock);
        Map<String, Object> props = new java.util.LinkedHashMap<>(stub.properties());
        props.putAll(extra);
        provider.configure(props);
        provider.init();
        return provider;
    }
}
