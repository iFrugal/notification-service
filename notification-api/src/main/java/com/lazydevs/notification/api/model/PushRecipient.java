package com.lazydevs.notification.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Push notification recipient details.
 *
 * <p>A push recipient names its target in one of these ways:
 * {@code deviceToken}, {@code deviceTokens}, {@code fid}, {@code topic} or
 * {@code condition}. The record does not enforce that exactly one is set;
 * each push provider validates the combination it supports and rejects the
 * rest as a permanent failure.
 *
 * @param id           optional recipient identifier for tracking
 * @param deviceToken  device token (FCM registration token or APNS device token)
 * @param topic        topic for topic-based messaging (optional, alternative to {@code deviceToken})
 * @param condition    condition for conditional messaging, e.g.
 *                     {@code "'TopicA' in topics && 'TopicB' in topics"} (optional)
 * @param title        push notification title
 * @param body         push notification body
 * @param data         custom data payload (key-value pairs)
 * @param badge        badge count for iOS
 * @param sound        sound to play
 * @param imageUrl     image URL for rich notifications
 * @param clickAction  click action / deep link
 * @param fid          Firebase installation id to target instead of a registration
 *                     token (optional; since 1.2.0). Omitted from JSON when {@code null}.
 * @param deviceTokens several device tokens to send the same message to (optional;
 *                     since 1.2.0). Omitted from JSON when {@code null} or empty.
 */
public record PushRecipient(
        String id,
        String deviceToken,
        String topic,
        String condition,
        String title,
        String body,
        Map<String, String> data,
        Integer badge,
        String sound,
        String imageUrl,
        String clickAction,
        @JsonInclude(JsonInclude.Include.NON_NULL) String fid,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> deviceTokens) implements Recipient {

    /**
     * The 1.1 shape, without {@code fid} and {@code deviceTokens}. Kept so
     * code compiled against 1.1 keeps working.
     */
    public PushRecipient(String id, String deviceToken, String topic, String condition, String title,
                         String body, Map<String, String> data, Integer badge, String sound,
                         String imageUrl, String clickAction) {
        this(id, deviceToken, topic, condition, title, body, data, badge, sound, imageUrl, clickAction,
                null, null);
    }

    @Override
    public String channelType() {
        return "PUSH";
    }
}
