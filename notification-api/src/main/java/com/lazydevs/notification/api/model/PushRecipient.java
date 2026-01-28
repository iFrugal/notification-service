package com.lazydevs.notification.api.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/**
 * Push notification recipient details.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PushRecipient extends Recipient {

    /**
     * Device token (FCM registration token or APNS device token)
     */
    private String deviceToken;

    /**
     * Topic for topic-based messaging (optional, alternative to deviceToken)
     */
    private String topic;

    /**
     * Condition for conditional messaging (optional, alternative to deviceToken)
     * Example: "'TopicA' in topics && 'TopicB' in topics"
     */
    private String condition;

    /**
     * Push notification title
     */
    private String title;

    /**
     * Push notification body
     */
    private String body;

    /**
     * Custom data payload (key-value pairs)
     */
    private Map<String, String> data;

    /**
     * Badge count for iOS
     */
    private Integer badge;

    /**
     * Sound to play
     */
    private String sound;

    /**
     * Image URL for rich notifications
     */
    private String imageUrl;

    /**
     * Click action / deep link
     */
    private String clickAction;

    @Override
    public String getChannelType() {
        return "PUSH";
    }
}
