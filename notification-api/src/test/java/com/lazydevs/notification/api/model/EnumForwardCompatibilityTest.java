package com.lazydevs.notification.api.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A constant added by a later version reads as {@code UNKNOWN} for readers
 * that enable {@code READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE}, as the
 * Redis and JDBC stores do since 1.1.2.
 */
class EnumForwardCompatibilityTest {

    private final ObjectMapper tolerant = new ObjectMapper()
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);

    @Test
    void unknownFailureTypeReadsAsUnknown() throws Exception {
        assertThat(tolerant.readValue("\"SOMETHING_NEW\"", FailureType.class)).isEqualTo(FailureType.UNKNOWN);
        assertThat(tolerant.readValue("\"TRANSIENT\"", FailureType.class)).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void unknownDeliveryStatusReadsAsUnknown() throws Exception {
        assertThat(tolerant.readValue("\"SOMETHING_NEW\"", DeliveryStatus.class)).isEqualTo(DeliveryStatus.UNKNOWN);
        assertThat(tolerant.readValue("\"BOUNCED\"", DeliveryStatus.class)).isEqualTo(DeliveryStatus.BOUNCED);
    }
}
