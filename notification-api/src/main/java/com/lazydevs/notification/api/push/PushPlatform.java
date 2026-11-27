package com.lazydevs.notification.api.push;

/**
 * Platform of a registered push device (DD-37).
 *
 * @since 1.2.3
 */
public enum PushPlatform {

    /** An Android app. */
    ANDROID,

    /** An iOS or iPadOS app. */
    IOS,

    /** A browser using Web Push. */
    WEB
}
