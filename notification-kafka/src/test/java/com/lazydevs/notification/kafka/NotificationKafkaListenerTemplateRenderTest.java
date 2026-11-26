package com.lazydevs.notification.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.exception.TemplateRenderException;
import com.lazydevs.notification.api.exception.TemplateRenderException.Reason;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.core.config.NotificationProperties;
import lazydevs.persistence.connection.multitenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DD-27: a message whose template cannot render is rejected with one WARN (no stack trace)
 * and its offset commits; it is not retried and not dead-lettered.
 */
class NotificationKafkaListenerTemplateRenderTest {

    private final NotificationService notificationService = mock(NotificationService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(NotificationKafkaListener.class);
    private NotificationKafkaListener listener;

    @BeforeEach
    void setUp() {
        listener = new NotificationKafkaListener(notificationService, new NotificationProperties());
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        TenantContext.reset();
    }

    @Test
    void renderFailure_isLoggedOnceAtWarnWithoutStackTraceAndNotRethrown() {
        when(notificationService.send(any())).thenThrow(new TemplateRenderException(Reason.MODEL_INVALID,
                "acme", "WELCOME", "user.name", "Template WELCOME for tenant acme could not be rendered", null));
        NotificationRequest request = NotificationRequest.builder()
                .notificationType("WELCOME")
                .channel(Channel.EMAIL)
                .build();

        assertThatCode(() -> listener.handleNotification(request, "acme", null, "key\n1", 3, 42L))
                .doesNotThrowAnyException();

        assertThat(appender.list).filteredOn(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getThrowableProxy()).isNull();
                    assertThat(event.getFormattedMessage())
                            .startsWith("Rejected notification from Kafka: key=key_1, partition=3, offset=42")
                            .contains("TEMPLATE_MODEL_INVALID", "path=user.name");
                });
        assertThat(TenantContext.getTenantId()).isNull();
    }
}
