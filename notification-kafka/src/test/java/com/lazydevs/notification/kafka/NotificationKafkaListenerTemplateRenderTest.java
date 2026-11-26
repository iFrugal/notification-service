package com.lazydevs.notification.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.TemplateRenderException;
import com.lazydevs.notification.api.exception.TemplateRenderException.Reason;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.WhatsAppRecipient;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.service.DefaultNotificationService;
import com.lazydevs.notification.core.service.NotificationAuditService;
import com.lazydevs.notification.core.service.NotificationServiceExtensions;
import com.lazydevs.notification.core.template.ChannelContentRenderer;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import lazydevs.persistence.connection.multitenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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

    /**
     * A channel renderer's rule violation (a WhatsApp parameter with a new line) through the
     * real send path with validate-before-dispatch: the listener rejects it the same way, and
     * nothing is claimed, audited or sent.
     */
    @Test
    void channelRendererParameterViolation_isRejectedTheSameWay_withNothingStored() {
        NotificationProperties properties = new NotificationProperties();
        properties.getTemplate().setValidateBeforeDispatch(true);
        IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
        NotificationAuditService auditService = mock(NotificationAuditService.class);
        ProviderRegistry providerRegistry = mock(ProviderRegistry.class);
        ChannelContentRenderer whatsapp = new ChannelContentRenderer() {
            @Override
            public Channel channel() {
                return Channel.WHATSAPP;
            }

            @Override
            public RenderedContent render(NotificationRequest request) {
                throw new TemplateRenderException(Reason.PARAMETER_INVALID, null, "order-shipped", "customerName",
                        "WhatsApp template 'order-shipped': variable 'customerName' contains a new line or a tab",
                        null);
            }
        };
        NotificationService service = new DefaultNotificationService(properties, providerRegistry,
                mock(NotificationTemplateEngine.class), auditService, Optional.of(idempotencyStore),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                NotificationServiceExtensions.defaults().withRenderers(List.of(whatsapp)));
        listener = new NotificationKafkaListener(service, properties);
        NotificationRequest request = NotificationRequest.builder()
                .notificationType("order-shipped")
                .channel(Channel.WHATSAPP)
                .idempotencyKey("key-1")
                .recipient(new WhatsAppRecipient(null, "+15550100001", null, null, null, null, null))
                .build();

        assertThatCode(() -> listener.handleNotification(request, "acme", null, "k-1", 0, 7L))
                .doesNotThrowAnyException();

        assertThat(appender.list).filteredOn(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getThrowableProxy()).isNull();
                    assertThat(event.getFormattedMessage())
                            .startsWith("Rejected notification from Kafka: key=k-1, partition=0, offset=7")
                            .contains("TEMPLATE_PARAMETER_INVALID", "path=customerName");
                });
        verifyNoInteractions(idempotencyStore, auditService, providerRegistry);
    }
}
