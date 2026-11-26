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
    void ambiguousIsAKnownFailureType_andWritesByName() throws Exception {
        assertThat(tolerant.readValue("\"AMBIGUOUS\"", FailureType.class)).isEqualTo(FailureType.AMBIGUOUS);
        assertThat(tolerant.writeValueAsString(FailureType.AMBIGUOUS)).isEqualTo("\"AMBIGUOUS\"");
    }

    @Test
    void unknownDeliveryStatusReadsAsUnknown() throws Exception {
        assertThat(tolerant.readValue("\"SOMETHING_NEW\"", DeliveryStatus.class)).isEqualTo(DeliveryStatus.UNKNOWN);
        assertThat(tolerant.readValue("\"BOUNCED\"", DeliveryStatus.class)).isEqualTo(DeliveryStatus.BOUNCED);
    }

    @Test
    void sentAndRead_areKnownDeliveryStatuses_appendedAfterUnknown() throws Exception {
        assertThat(tolerant.readValue("\"SENT\"", DeliveryStatus.class)).isEqualTo(DeliveryStatus.SENT);
        assertThat(tolerant.readValue("\"READ\"", DeliveryStatus.class)).isEqualTo(DeliveryStatus.READ);
        assertThat(tolerant.writeValueAsString(DeliveryStatus.READ)).isEqualTo("\"READ\"");
        // Ordinals of the 1.2.0 constants are unchanged; new constants are appended.
        assertThat(DeliveryStatus.UNKNOWN.ordinal()).isEqualTo(4);
        assertThat(DeliveryStatus.SENT.ordinal()).isEqualTo(5);
        assertThat(DeliveryStatus.READ.ordinal()).isEqualTo(6);
    }
}
