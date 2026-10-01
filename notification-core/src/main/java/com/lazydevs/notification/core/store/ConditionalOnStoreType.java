package com.lazydevs.notification.core.store;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Matches when the given {@link StoreFeature} is enabled and its store
 * family resolves to {@link #type()}.
 * See {@link StoreFeature} for the resolution rule.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnStoreTypeCondition.class)
public @interface ConditionalOnStoreType {

    /** The feature whose store family is checked. */
    StoreFeature feature();

    /** The store family that must be selected for the feature. */
    StoreType type();
}
