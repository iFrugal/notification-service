package com.lazydevs.notification.core.push;

import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventListener;
import com.lazydevs.notification.api.delivery.DeliveryEvents;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.push.PushDeviceRegistry;
import com.lazydevs.notification.api.push.PushDeviceRemovalReasons;
import com.lazydevs.notification.api.push.PushTokens;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Removes a registered push device once its push service reported the token as
 * invalid (DD-37), so the next notification to the user does not try it again.
 *
 * <p>Reacts to a {@link DeliveryStatus#BOUNCED} event with reason
 * {@link DeliveryEvents#REASON_INVALID_TARGET} and a {@link DeliveryEvents#ATTR_TOKEN_HASH}
 * attribute, as the FCM provider publishes on the sending thread: the registry removes
 * every device with that token hash in the current tenant scope (reason
 * {@link PushDeviceRemovalReasons#INVALID_TARGET}). Every other event is ignored.
 *
 * <p>The hash keeps 64 bits of SHA-256, so two tokens of one scope share a hash with a
 * probability of about {@code n / 2^64} per bounce for {@code n} devices; the cost of that
 * collision is one device registering again.
 *
 * <p>Never throws, as the {@link DeliveryEventListener} contract asks: a registry failure is
 * logged and the device stays until the next bounce or the purge.
 *
 * @since 1.2.3
 */
@Slf4j
public class PushDeviceCleanupListener implements DeliveryEventListener {

    private static final Pattern TOKEN_HASH = Pattern.compile(
            Pattern.quote(PushTokens.HASH_PREFIX) + "[0-9a-f]{" + PushTokens.HASH_HEX_LENGTH + "}");

    private final PushDeviceRegistry registry;

    /**
     * @param registry the registry to remove devices from
     */
    public PushDeviceCleanupListener(PushDeviceRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void onEvent(DeliveryEvent event) {
        String tokenHash = invalidTokenHash(event);
        if (tokenHash == null) {
            return;
        }
        try {
            int removed = registry.removeByTokenHash(tokenHash, PushDeviceRemovalReasons.INVALID_TARGET);
            log.debug("Invalid push target: {} device(s) removed", removed);
        } catch (RuntimeException e) {
            log.warn("Removing the push device of an invalid target failed: {}", e.getClass().getSimpleName());
        }
    }

    /** The token hash of an invalid-target bounce, or {@code null} for any other event. */
    private static String invalidTokenHash(DeliveryEvent event) {
        if (event == null || event.status() != DeliveryStatus.BOUNCED
                || !DeliveryEvents.REASON_INVALID_TARGET.equals(event.reason())) {
            return null;
        }
        Map<String, String> attributes = event.attributes();
        String hash = attributes == null ? null : attributes.get(DeliveryEvents.ATTR_TOKEN_HASH);
        // Only the exact shape: anything else did not come from PushTokens.hash.
        return hash != null && TOKEN_HASH.matcher(hash).matches() ? hash : null;
    }
}
