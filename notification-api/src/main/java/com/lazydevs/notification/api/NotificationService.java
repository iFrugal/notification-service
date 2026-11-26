package com.lazydevs.notification.api;

import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.NotificationView;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Main service interface for sending notifications.
 * This is the primary API for library users (when using as starter).
 */
public interface NotificationService {

    /**
     * Send a notification synchronously.
     *
     * @param request the notification request
     * @return the notification response
     */
    NotificationResponse send(NotificationRequest request);

    /**
     * Send a notification asynchronously.
     *
     * @param request the notification request
     * @return a future that completes with the response
     */
    CompletableFuture<NotificationResponse> sendAsync(NotificationRequest request);

    /**
     * Send multiple notifications in batch.
     *
     * @param requests the list of notification requests
     * @return list of responses (in same order as requests)
     */
    List<NotificationResponse> sendBatch(List<NotificationRequest> requests);

    /**
     * Send multiple notifications asynchronously.
     *
     * @param requests the list of notification requests
     * @return a future that completes with all responses
     */
    CompletableFuture<List<NotificationResponse>> sendBatchAsync(List<NotificationRequest> requests);

    /**
     * Status of one notification (DD-33): the audit record joined with the
     * latest delivery event. Needs {@code notification.audit.enabled=true};
     * without an audit store the result is always empty.
     *
     * @param tenantId  tenant of the request; {@code null} or blank means the
     *                  default tenant
     * @param requestId request id returned by {@link #send}
     * @return the view, or empty when unknown (never sent, expired, or audit off)
     * @since 1.2.1
     */
    default Optional<NotificationView> find(String tenantId, String requestId) {
        return Optional.empty();
    }

    /**
     * Status of the notification sent under an idempotency key (DD-33): the
     * most recent request that used the key. An idempotent replay creates no
     * record of its own, so this is the view of the request whose outcome is
     * being replayed.
     *
     * @param tenantId       tenant; {@code null} or blank means the default tenant
     * @param callerId       calling service, may be {@code null}
     * @param idempotencyKey the key
     * @return the view, or empty when unknown
     * @since 1.2.1
     */
    default Optional<NotificationView> findByIdempotencyKey(String tenantId, String callerId,
                                                            String idempotencyKey) {
        return Optional.empty();
    }
}
