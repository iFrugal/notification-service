package com.lazydevs.notification.core.store;

import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;

/**
 * A stateful notification feature whose store family is selectable.
 *
 * <p>The family for a feature resolves in this order:
 * <ol>
 *   <li>the feature is active only when {@code notification.<feature>.enabled}
 *       is {@code true} (idempotency defaults to on, the others to off);</li>
 *   <li>an explicit {@code notification.redis.<feature>.enabled} wins:
 *       {@code true} selects {@link StoreType#REDIS},
 *       {@code false} selects {@link StoreType#MEMORY};</li>
 *   <li>otherwise {@code notification.store.type} decides, defaulting to
 *       {@link StoreType#MEMORY}.</li>
 * </ol>
 * Properties are read with a {@link Binder}, so relaxed names and
 * environment variables work exactly as they do for
 * {@code NotificationProperties}.
 */
public enum StoreFeature {

    IDEMPOTENCY("idempotency", true),
    RATE_LIMIT("rate-limit", false),
    DEAD_LETTER("dead-letter", false),
    DELIVERY_EVENTS("delivery-events", false);

    /** The global store family property. */
    public static final String STORE_TYPE_PROPERTY = "notification.store.type";

    private final String id;
    private final boolean enabledByDefault;

    StoreFeature(String id, boolean enabledByDefault) {
        this.id = id;
        this.enabledByDefault = enabledByDefault;
    }

    /** @return the kebab-case property segment, for example {@code rate-limit} */
    public String id() {
        return id;
    }

    /** @return the feature's own switch, for example {@code notification.rate-limit.enabled} */
    public String enabledProperty() {
        return "notification." + id + ".enabled";
    }

    /** @return the per-feature Redis toggle, for example {@code notification.redis.rate-limit.enabled} */
    public String redisToggleProperty() {
        return "notification.redis." + id + ".enabled";
    }

    /** @return whether the feature is switched on in the given configuration */
    public boolean isEnabled(Binder binder) {
        return binder.bind(enabledProperty(), Boolean.class).orElse(enabledByDefault);
    }

    /**
     * Resolves the store family for this feature, assuming it is enabled.
     *
     * @return the family and a human-readable reason naming the deciding property
     */
    public Resolution resolve(Binder binder) {
        BindResult<Boolean> redisToggle = binder.bind(redisToggleProperty(), Boolean.class);
        if (redisToggle.isBound()) {
            StoreType type = Boolean.TRUE.equals(redisToggle.get()) ? StoreType.REDIS : StoreType.MEMORY;
            return new Resolution(type, redisToggleProperty() + "=" + redisToggle.get());
        }
        BindResult<StoreType> storeType = binder.bind(STORE_TYPE_PROPERTY, StoreType.class);
        if (storeType.isBound()) {
            return new Resolution(storeType.get(), STORE_TYPE_PROPERTY + "=" + storeType.get().id());
        }
        return new Resolution(StoreType.MEMORY, STORE_TYPE_PROPERTY + " is not set (defaults to memory)");
    }

    /**
     * The resolved store family of a feature.
     *
     * @param type   the selected family
     * @param reason which property decided it, for condition reports and errors
     */
    public record Resolution(StoreType type, String reason) {
    }
}
