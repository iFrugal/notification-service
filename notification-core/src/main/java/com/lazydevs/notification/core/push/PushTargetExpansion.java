package com.lazydevs.notification.core.push;

import com.lazydevs.notification.api.exception.NotificationException;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.PushRecipient;
import com.lazydevs.notification.api.push.PushDevice;
import com.lazydevs.notification.api.push.PushDeviceRegistry;
import com.lazydevs.notification.api.push.PushTargeting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves a push recipient that addresses a user (DD-37) into the tokens of the
 * user's active registered devices, one step of the send pipeline.
 *
 * <p>It runs after the idempotency claim and the rate limit and before the provider is
 * called, on the request as dispatched: the request the caller sent, which is what the
 * idempotency record, the audit record and a dead letter keep, still names the user, so
 * a replay resolves the devices again and tokens never leave the registry and the provider
 * call. Every other request passes through unchanged.
 *
 * <ul>
 *   <li>A {@code userId} together with another target fails with
 *       {@link PushTargeting#INVALID_USER_TARGET}.</li>
 *   <li>Without a registry it fails with {@link PushTargeting#USER_TARGETING_DISABLED}.</li>
 *   <li>A user without an active device fails with {@link PushTargeting#NO_DEVICES}.</li>
 *   <li>Otherwise the recipient becomes {@code deviceTokens}, most recently seen device
 *       first, and the request's metadata gets
 *       {@link PushTargeting#MULTI_TOKEN_POLICY_METADATA} unless the caller set it.</li>
 * </ul>
 * Each failure is a {@link NotificationException}, which the pipeline answers with a
 * permanent failure.
 *
 * @since 1.4.0
 */
public final class PushTargetExpansion {

    /** The multi-token policy values a request may carry. */
    private static final Set<String> POLICIES = Set.of("any", "all");

    private final PushDeviceRegistry registry;
    private final String multiTokenPolicy;

    /**
     * @param registry         the device registry, {@code null} when user targeting is off
     * @param multiTokenPolicy {@code any} or {@code all}, for requests that do not name one
     */
    public PushTargetExpansion(PushDeviceRegistry registry, String multiTokenPolicy) {
        String policy = Objects.requireNonNull(multiTokenPolicy, "multiTokenPolicy").strip().toLowerCase(Locale.ROOT);
        if (!POLICIES.contains(policy)) {
            throw new IllegalArgumentException("multiTokenPolicy must be 'any' or 'all', got '" + multiTokenPolicy + "'");
        }
        this.registry = registry;
        this.multiTokenPolicy = policy;
    }

    /**
     * @return an expansion without a registry: a user target fails with
     *         {@link PushTargeting#USER_TARGETING_DISABLED}, every other request passes
     */
    public static PushTargetExpansion disabled() {
        return new PushTargetExpansion(null, "any");
    }

    /** @return whether a registry is configured */
    public boolean isEnabled() {
        return registry != null;
    }

    /**
     * @param request the request as dispatched
     * @return {@code request} itself when it does not address a user, else a copy that
     *         addresses the user's active devices
     * @throws NotificationException when the user target cannot be resolved
     */
    public NotificationRequest expand(NotificationRequest request) {
        if (!(request.getRecipient() instanceof PushRecipient push) || !push.isUserTarget()) {
            return request;
        }
        List<String> others = otherTargets(push);
        if (!others.isEmpty()) {
            throw new NotificationException(PushTargeting.INVALID_USER_TARGET, "the push recipient names a userId and "
                    + others + "; a userId is the only target of a recipient that names it");
        }
        if (registry == null) {
            throw new NotificationException(PushTargeting.USER_TARGETING_DISABLED, "the push recipient names a"
                    + " userId, but no push device registry is configured; set notification.push.devices.enabled=true"
                    + " or declare a PushDeviceRegistry bean");
        }
        List<PushDevice> devices = registry.activeDevices(push.userId(), push.appId());
        Set<String> tokens = new LinkedHashSet<>();
        for (PushDevice device : devices) {
            tokens.add(device.token());
        }
        if (tokens.isEmpty()) {
            throw new NotificationException(PushTargeting.NO_DEVICES, "the user has no active push device"
                    + (push.appId() == null ? "" : " for this app"));
        }
        return request.toBuilder()
                .recipient(push.withDeviceTokens(new ArrayList<>(tokens)))
                .metadata(withPolicy(request.getMetadata()))
                .build();
    }

    private Map<String, String> withPolicy(Map<String, String> metadata) {
        if (metadata != null && metadata.containsKey(PushTargeting.MULTI_TOKEN_POLICY_METADATA)) {
            return metadata;
        }
        Map<String, String> copy = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        copy.put(PushTargeting.MULTI_TOKEN_POLICY_METADATA, multiTokenPolicy);
        return copy;
    }

    private static List<String> otherTargets(PushRecipient push) {
        List<String> named = new ArrayList<>();
        if (hasText(push.deviceToken())) {
            named.add("deviceToken");
        }
        if (push.deviceTokens() != null && !push.deviceTokens().isEmpty()) {
            named.add("deviceTokens");
        }
        if (hasText(push.fid())) {
            named.add("fid");
        }
        if (hasText(push.topic())) {
            named.add("topic");
        }
        if (hasText(push.condition())) {
            named.add("condition");
        }
        return named;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
