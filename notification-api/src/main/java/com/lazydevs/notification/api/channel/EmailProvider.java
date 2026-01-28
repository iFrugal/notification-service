package com.lazydevs.notification.api.channel;

import com.lazydevs.notification.api.Channel;

/**
 * Email channel provider interface.
 */
public interface EmailProvider extends NotificationProvider {

    @Override
    default Channel getChannel() {
        return Channel.EMAIL;
    }
}
