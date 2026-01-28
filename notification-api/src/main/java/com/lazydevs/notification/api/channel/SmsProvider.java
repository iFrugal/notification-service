package com.lazydevs.notification.api.channel;

import com.lazydevs.notification.api.Channel;

/**
 * SMS channel provider interface.
 */
public interface SmsProvider extends NotificationProvider {

    @Override
    default Channel getChannel() {
        return Channel.SMS;
    }
}
