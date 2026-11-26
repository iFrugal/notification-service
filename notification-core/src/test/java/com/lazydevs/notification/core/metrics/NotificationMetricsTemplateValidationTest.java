package com.lazydevs.notification.core.metrics;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.exception.TemplateRenderException.Reason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-27: requests refused by validate-before-dispatch are counted by channel and reason.
 */
class NotificationMetricsTemplateValidationTest {

    private static final String METER = "notification.template.validation.failed.total";

    @Test
    void recordTemplateValidationFailed_isTaggedByChannelAndReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationMetrics metrics = new NotificationMetrics(registry, Optional.empty(), Optional.empty());

        metrics.recordTemplateValidationFailed(Channel.EMAIL, Reason.MODEL_INVALID);
        metrics.recordTemplateValidationFailed(Channel.EMAIL, Reason.MODEL_INVALID);
        metrics.recordTemplateValidationFailed(Channel.SMS, Reason.NOT_FOUND);
        metrics.recordTemplateValidationFailed(null, null);

        assertThat(registry.counter(METER, "channel", "EMAIL", "reason", "MODEL_INVALID").count()).isEqualTo(2.0);
        assertThat(registry.counter(METER, "channel", "SMS", "reason", "NOT_FOUND").count()).isEqualTo(1.0);
        assertThat(registry.counter(METER, "channel", "unknown", "reason", "UNKNOWN").count()).isEqualTo(1.0);
    }
}
