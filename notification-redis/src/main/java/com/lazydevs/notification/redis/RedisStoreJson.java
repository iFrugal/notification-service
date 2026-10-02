package com.lazydevs.notification.redis;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The {@link ObjectMapper} behind every Redis store's JSON values.
 *
 * <p>Several library versions can share one Redis during a rolling upgrade,
 * so reads are tolerant in both directions: unknown properties are ignored,
 * and an enum constant this version does not know reads as the constant
 * marked {@code @JsonEnumDefaultValue} ({@code UNKNOWN} for
 * {@code FailureType} and {@code DeliveryStatus}). The written format is
 * unchanged from 1.1.1.
 *
 * <p>The host application's {@code ObjectMapper} is deliberately not used,
 * so the stored format does not change with the host's Jackson settings.
 *
 * @since 1.1.2
 */
final class RedisStoreJson {

    private RedisStoreJson() {
    }

    static ObjectMapper create() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);
    }
}
