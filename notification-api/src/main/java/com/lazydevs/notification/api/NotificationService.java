package com.lazydevs.notification.api;

import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;

import java.util.List;
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
}
