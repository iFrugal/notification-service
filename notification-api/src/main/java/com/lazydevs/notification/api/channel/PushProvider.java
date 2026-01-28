package com.lazydevs.notification.api.channel;

import com.lazydevs.notification.api.Channel;

/**
 * Push notification channel provider interface.
 */
public interface PushProvider extends NotificationProvider {

    @Override
    default Channel getChannel() {
        return Channel.PUSH;
    }
}
