package com.lazydevs.notification.api.channel;

import com.lazydevs.notification.api.Channel;

/**
 * WhatsApp channel provider interface.
 */
public interface WhatsAppProvider extends NotificationProvider {

    @Override
    default Channel getChannel() {
        return Channel.WHATSAPP;
    }
}
