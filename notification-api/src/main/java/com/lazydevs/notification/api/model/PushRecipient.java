package com.lazydevs.notification.api.model;

import java.util.Map;

/**
 * Push notification recipient details.
 *
 * @param id          optional recipient identifier for tracking
 * @param deviceToken device token (FCM registration token or APNS device token)
 * @param topic       topic for topic-based messaging (optional, alternative to {@code deviceToken})
 * @param condition   condition for conditional messaging, e.g.
 *                    {@code "'TopicA' in topics && 'TopicB' in topics"} (optional)
 * @param title       push notification title
 * @param body        push notification body
 * @param data        custom data payload (key-value pairs)
 * @param badge       badge count for iOS
 * @param sound       sound to play
 * @param imageUrl    image URL for rich notifications
 * @param clickAction click action / deep link
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
        String clickAction) implements Recipient {

    @Override
    public String channelType() {
        return "PUSH";
    }
}
