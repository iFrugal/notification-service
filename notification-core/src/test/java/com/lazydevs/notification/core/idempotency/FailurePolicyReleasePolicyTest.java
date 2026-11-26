package com.lazydevs.notification.core.idempotency;

import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.core.config.NotificationProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class FailurePolicyReleasePolicyTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "RELEASE_ALL, PERMANENT, true",
            "RELEASE_ALL, null, true",
            "KEEP, TRANSIENT, false",
            "RELEASE_TRANSIENT, TRANSIENT, true",
            "RELEASE_TRANSIENT, AMBIGUOUS, false",
            "RELEASE_TRANSIENT, null, false"})
    void followsTheConfiguredPolicy_readOnEveryCall(IdempotencyFailurePolicy policy, FailureType type,
                                                    boolean expected) {
        NotificationProperties properties = new NotificationProperties();
        FailurePolicyReleasePolicy releasePolicy = new FailurePolicyReleasePolicy(properties);
        properties.getIdempotency().setFailurePolicy(policy);
        NotificationRequest request = NotificationRequest.builder().requestId("r").build();
        NotificationResponse failed = NotificationResponse.failure(request, "smtp", "E", "m").withFailureType(type);

        assertThat(releasePolicy.releases(request, failed)).isEqualTo(expected);
    }
}
