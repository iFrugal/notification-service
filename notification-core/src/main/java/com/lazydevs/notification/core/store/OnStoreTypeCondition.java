package com.lazydevs.notification.core.store;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * Backs {@link ConditionalOnStoreType}: resolves the store family of a
 * feature with {@link StoreFeature#resolve(Binder)} and matches when it
 * equals the requested one.
 */
class OnStoreTypeCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnStoreType.class.getName());
        if (attributes == null) {
            throw new IllegalStateException("@ConditionalOnStoreType attributes not found on " + metadata);
        }
        StoreFeature feature = (StoreFeature) attributes.get("feature");
        StoreType required = (StoreType) attributes.get("type");
        ConditionMessage.Builder message = ConditionMessage.forCondition(ConditionalOnStoreType.class,
                "(feature=" + feature.id() + ", type=" + required.id() + ")");

        Binder binder = Binder.get(context.getEnvironment());
        if (!feature.isEnabled(binder)) {
            return ConditionOutcome.noMatch(message.because(
                    "feature " + feature.id() + " is disabled (" + feature.enabledProperty() + " is not true)"));
        }
        StoreFeature.Resolution resolution = feature.resolve(binder);
        String because = "feature " + feature.id() + " resolved to store family '"
                + resolution.type().id() + "' because " + resolution.reason();
        return resolution.type() == required
                ? ConditionOutcome.match(message.because(because))
                : ConditionOutcome.noMatch(message.because(because));
    }
}
