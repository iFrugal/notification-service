package com.lazydevs.notification.api.push;

/**
 * Why a {@link PushDeviceRegistry} removed a device (DD-37). Every removal is
 * logged at INFO with the token hash and one of these reasons, never the token.
 *
 * @since 1.2.3
 */
public final class PushDeviceRemovalReasons {

    /** The app or the user unregistered the device. */
    public static final String UNREGISTERED = "unregistered";

    /** The token was registered again by another user or installation in the same scope. */
    public static final String MOVED = "moved";

    /** The user registered more than {@code max-devices-per-user} devices; the least recently seen went. */
    public static final String EVICTED = "evicted";

    /** The push service reported the token as invalid or no longer registered. */
    public static final String INVALID_TARGET = "invalid_target";

    /** The device was not seen for longer than the purge age. */
    public static final String STALE = "stale";

    private PushDeviceRemovalReasons() {
    }
}
