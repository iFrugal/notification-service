package com.lazydevs.notification.api.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DD-31: {@code failureType} and {@code providerMetadata} on {@link NotificationResponse}.
 */
class NotificationResponseFailureTypeTest {

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);

    @Test
    void newFieldsAreOmittedFromJsonWhenNull() throws Exception {
        NotificationResponse sent = NotificationResponse.sent(request(), "acs", "op-1", Instant.now(), Instant.now());

        String body = json.writeValueAsString(sent);

        assertThat(body).doesNotContain("failureType").doesNotContain("providerMetadata")
                .doesNotContain("idempotentReplay");
    }

    @Test
    void failureTypeAndMetadataRoundTrip() throws Exception {
        NotificationResponse failed = NotificationResponse.failed(request(), "acs", "op-1", "ACS_TIMEOUT", "slow",
                        Instant.parse("2026-01-01T00:00:00Z"))
                .withFailureType(FailureType.AMBIGUOUS)
                .withProviderMetadata(Map.of("acsStatus", "UNCONFIRMED"));

        String body = json.writeValueAsString(failed);
        NotificationResponse read = json.readValue(body, NotificationResponse.class);

        assertThat(body).contains("\"failureType\":\"AMBIGUOUS\"").contains("\"acsStatus\":\"UNCONFIRMED\"");
        assertThat(read.failureType()).isEqualTo(FailureType.AMBIGUOUS);
        assertThat(read.providerMetadata()).containsEntry("acsStatus", "UNCONFIRMED");
        assertThat(read.errorCode()).isEqualTo("ACS_TIMEOUT");
    }

    @Test
    void jsonWrittenBy120ReadsWithNullNewFields() throws Exception {
        String legacy = """
                {"requestId":"r1","tenantId":"acme","channel":"EMAIL","status":"FAILED","errorCode":"X"}""";

        NotificationResponse read = json.readValue(legacy, NotificationResponse.class);

        assertThat(read.failureType()).isNull();
        assertThat(read.providerMetadata()).isNull();
        assertThat(read.status()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void unknownFailureTypeFromANewerVersionReadsAsUnknown() throws Exception {
        String newer = """
                {"requestId":"r1","status":"FAILED","failureType":"SOMETHING_NEW"}""";

        assertThat(json.readValue(newer, NotificationResponse.class).failureType()).isEqualTo(FailureType.UNKNOWN);
    }

    @Test
    void legacyConstructorLeavesNewFieldsNull() {
        NotificationResponse legacy = new NotificationResponse("r1", null, "acme", null, Channel.EMAIL, "acs",
                NotificationStatus.SENT, "op", null, null, null, null, null, null);

        assertThat(legacy.failureType()).isNull();
        assertThat(legacy.providerMetadata()).isNull();
    }

    @Test
    void replayKeepsFailureTypeAndMetadata() {
        NotificationResponse failed = NotificationResponse.failure(request(), "acs", "E", "m")
                .withFailureType(FailureType.PERMANENT)
                .withProviderMetadata(Map.of("k", "v"));

        NotificationResponse replay = NotificationResponse.replayedFrom(failed);

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(replay.providerMetadata()).containsEntry("k", "v");
    }

    @Test
    void providerMetadataIsCopiedAndUnmodifiable_andEmptyBecomesNull() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("k", "v");
        NotificationResponse response = NotificationResponse.failure(request(), "acs", "E", "m")
                .withProviderMetadata(metadata);
        metadata.put("k2", "v2");

        assertThat(response.providerMetadata()).containsOnlyKeys("k");
        assertThatThrownBy(() -> response.providerMetadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(response.withProviderMetadata(Map.of()).providerMetadata()).isNull();
        assertThat(response.withErrorMessage("scrubbed").errorMessage()).isEqualTo("scrubbed");
    }

    private static NotificationRequest request() {
        return NotificationRequest.builder()
                .requestId("r1").tenantId("acme").channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "a@example.com", null, null, null, null))
                .build();
    }
}
