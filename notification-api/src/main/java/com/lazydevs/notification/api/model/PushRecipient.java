package com.lazydevs.notification.api.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Push notification recipient details.
 *
 * <p>A push recipient names its target in one of these ways:
 * {@code deviceToken}, {@code deviceTokens}, {@code fid}, {@code topic},
 * {@code condition} or {@code userId}. The record does not enforce that exactly
 * one is set; each push provider validates the combination it supports and
 * rejects the rest as a permanent failure.
 *
 * <p>A {@code userId} target (since 1.4.0, DD-37) addresses every active device the
 * push device registry holds for the user, optionally only those of {@code appId}.
 * The send pipeline replaces it with those devices' tokens before the provider is
 * called ({@link #withDeviceTokens(List)}), so a stored request keeps the user id
 * and never the tokens.
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
 * @param userId       the user whose registered devices receive the message (optional;
 *                     since 1.4.0). Omitted from JSON when {@code null}.
 * @param appId        with {@code userId}: only devices of this app (optional; since 1.4.0).
 *                     Omitted from JSON when {@code null}.
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
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> deviceTokens,
        @JsonInclude(JsonInclude.Include.NON_NULL) String userId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String appId) implements Recipient {

    /**
     * The 1.2 shape, without {@code userId} and {@code appId}. Kept so code
     * compiled against 1.2 and 1.3 keeps working.
     */
    @SuppressWarnings("java:S107") // one parameter per record component of the 1.2 shape
    public PushRecipient(String id, String deviceToken, String topic, String condition, String title,
                         String body, Map<String, String> data, Integer badge, String sound,
                         String imageUrl, String clickAction, String fid, List<String> deviceTokens) {
        this(id, deviceToken, topic, condition, title, body, data, badge, sound, imageUrl, clickAction,
                fid, deviceTokens, null, null);
    }

    /**
     * The 1.1 shape, without {@code fid} and {@code deviceTokens}. Kept so
     * code compiled against 1.1 keeps working.
     */
    public PushRecipient(String id, String deviceToken, String topic, String condition, String title,
                         String body, Map<String, String> data, Integer badge, String sound,
                         String imageUrl, String clickAction) {
        this(id, deviceToken, topic, condition, title, body, data, badge, sound, imageUrl, clickAction,
                null, null, null, null);
    }

    /**
     * A recipient that addresses every active registered device of a user (DD-37).
     * Set the title, body and data with the {@code with...} copies or through a template.
     *
     * @param userId the user, not blank
     * @return the recipient
     * @since 1.4.0
     */
    public static PushRecipient forUser(String userId) {
        return forUser(userId, null);
    }

    /**
     * A recipient that addresses the active registered devices of a user in one app (DD-37).
     *
     * @param userId the user, not blank
     * @param appId  only devices of this app; {@code null} for every app
     * @return the recipient
     * @since 1.4.0
     */
    public static PushRecipient forUser(String userId, String appId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        return new PushRecipient(null, null, null, null, null, null, null, null, null, null, null,
                null, null, userId, appId);
    }

    /**
     * @return whether this recipient addresses a user's registered devices
     * @since 1.4.0
     */
    @JsonIgnore
    public boolean isUserTarget() {
        return userId != null && !userId.isBlank();
    }

    /**
     * This recipient with {@code tokens} as its only target: the user target
     * ({@code userId} and {@code appId}) replaced by the devices it resolved to.
     * Everything else is kept.
     *
     * @param tokens the device tokens, not empty
     * @return the copy
     * @since 1.4.0
     */
    public PushRecipient withDeviceTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            throw new IllegalArgumentException("tokens must not be empty");
        }
        return new PushRecipient(id, null, null, null, title, body, data, badge, sound, imageUrl, clickAction,
                null, List.copyOf(tokens), null, null);
    }

    @Override
    public String channelType() {
        return "PUSH";
    }
}
