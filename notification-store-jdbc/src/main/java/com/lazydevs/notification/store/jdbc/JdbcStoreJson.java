package com.lazydevs.notification.store.jdbc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Builds the {@link ObjectMapper} the JDBC stores use for their JSON text
 * columns.
 */
public final class JdbcStoreJson {

    private JdbcStoreJson() {
    }

    /**
     * A new module-owned mapper for the JSON columns: {@code java.time}
     * support, ISO-8601 timestamps, and tolerance for unknown properties so
     * rows written by a newer library version still read. An enum constant
     * this version does not know reads as the constant marked
     * {@code @JsonEnumDefaultValue} ({@code UNKNOWN} for {@code FailureType}
     * and {@code DeliveryStatus}, since 1.1.2).
     *
     * <p>The host application's {@code ObjectMapper} is deliberately never
     * used, so the stored format does not change when the host changes its
     * Jackson configuration (naming strategy, inclusion rules, modules).
     */
    public static ObjectMapper create() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);
    }
}
