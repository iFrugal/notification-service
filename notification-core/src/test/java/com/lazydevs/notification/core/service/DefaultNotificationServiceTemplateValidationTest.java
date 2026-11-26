package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.exception.TemplateNotFoundException;
import com.lazydevs.notification.api.exception.TemplateRenderException;
import com.lazydevs.notification.api.exception.TemplateRenderException.Reason;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.metrics.NotificationMetrics;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DD-27: {@code notification.template.validate-before-dispatch} renders first and refuses a
 * request whose template cannot render before anything is claimed, counted or stored.
 */
@ExtendWith(MockitoExtension.class)
class DefaultNotificationServiceTemplateValidationTest {

    @Mock ProviderRegistry providerRegistry;
    @Mock NotificationTemplateEngine templateEngine;
    @Mock NotificationAuditService auditService;
    @Mock IdempotencyStore idempotencyStore;
    @Mock RateLimiter rateLimiter;
    @Mock DeadLetterStore deadLetterStore;
    @Mock NotificationMetrics metrics;
    @Mock NotificationProvider provider;

    private final NotificationProperties properties = new NotificationProperties();
    private DefaultNotificationService service;

    @BeforeEach
    void setUp() {
        properties.getTemplate().setValidateBeforeDispatch(true);
        service = service(Optional.of(metrics));
    }

    private DefaultNotificationService service(Optional<NotificationMetrics> withMetrics) {
        return new DefaultNotificationService(
                properties, providerRegistry, templateEngine, auditService,
                Optional.of(idempotencyStore), Optional.of(rateLimiter),
                Optional.empty(), Optional.of(deadLetterStore), withMetrics);
    }

    private static NotificationRequest request(Channel channel) {
        return NotificationRequest.builder()
                .requestId("req-1")
                .tenantId("acme")
                .idempotencyKey("key-1")
                .notificationType("WELCOME")
                .channel(channel)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .build();
    }

    private static TemplateRenderException modelInvalid() {
        return new TemplateRenderException(Reason.MODEL_INVALID, "acme", "WELCOME", "user.name",
                "Template WELCOME for tenant acme could not be rendered: user.name is missing", null);
    }

    @Test
    void renderFailure_isThrownBeforeIdempotencyRateLimitAuditAndDlq() {
        TemplateRenderException failure = modelInvalid();
        when(templateEngine.renderAndValidate(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.send(request(Channel.EMAIL))).isSameAs(failure);

        verifyNoInteractions(idempotencyStore, rateLimiter, auditService, deadLetterStore, providerRegistry);
        verify(templateEngine, never()).render(any());
        verify(metrics).recordTemplateValidationFailed(Channel.EMAIL, Reason.MODEL_INVALID);
        verify(metrics, never()).recordSend(any(), any(), any());
    }

    @Test
    void missingTemplate_isThrownAsTemplateNotFoundException() {
        when(templateEngine.renderAndValidate(any()))
                .thenThrow(new TemplateNotFoundException("acme", "email", "WELCOME"));

        NotificationRequest request = request(Channel.EMAIL);
        assertThatThrownBy(() -> service.send(request))
                .isInstanceOf(TemplateNotFoundException.class);

        verify(metrics).recordTemplateValidationFailed(Channel.EMAIL, Reason.NOT_FOUND);
        verifyNoInteractions(idempotencyStore, rateLimiter, auditService, deadLetterStore);
    }

    @Test
    void renderFailure_withoutMetrics_isStillThrown() {
        service = service(Optional.empty());
        when(templateEngine.renderAndValidate(any())).thenThrow(modelInvalid());

        NotificationRequest request = request(Channel.EMAIL);
        assertThatThrownBy(() -> service.send(request)).isInstanceOf(TemplateRenderException.class);
    }

    @Test
    void success_dispatchesThePreRenderedContentWithoutRenderingAgain() {
        RenderedContent content = new RenderedContent("Hi", "Body", null, "WELCOME");
        when(templateEngine.renderAndValidate(any())).thenReturn(content);
        when(idempotencyStore.findExisting(any())).thenReturn(Optional.empty());
        when(idempotencyStore.markInProgress(any(), anyString(), anyString())).thenReturn(true);
        when(rateLimiter.tryConsume(any())).thenReturn(RateLimiter.Decision.allow());
        when(providerRegistry.getProvider(anyString(), any(Channel.class), any())).thenReturn(provider);
        when(provider.send(any(), same(content))).thenReturn(SendResult.success("msg-1"));
        when(provider.getProviderName()).thenReturn("smtp");

        NotificationResponse response = service.send(request(Channel.EMAIL));

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        verify(templateEngine).renderAndValidate(any());
        verify(templateEngine, never()).render(any());
        verify(provider).send(any(), same(content));
    }

    @Test
    void flagOff_rendersAtDispatchAndStoresTheFailureAsIn12() {
        properties.getTemplate().setValidateBeforeDispatch(false);
        when(idempotencyStore.findExisting(any())).thenReturn(Optional.empty());
        when(idempotencyStore.markInProgress(any(), anyString(), anyString())).thenReturn(true);
        when(rateLimiter.tryConsume(any())).thenReturn(RateLimiter.Decision.allow());
        when(templateEngine.render(any())).thenThrow(modelInvalid());

        NotificationResponse response = service.send(request(Channel.EMAIL));

        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(response.errorCode()).isEqualTo("TEMPLATE_MODEL_INVALID");
        verify(templateEngine, never()).renderAndValidate(any());
        verify(auditService).recordReceived(any());
        verify(deadLetterStore).add(any());
        verify(metrics, never()).recordTemplateValidationFailed(any(), any());
    }

    @Test
    void requestWithoutChannel_isLeftToTheDispatchPath() {
        when(idempotencyStore.findExisting(any())).thenReturn(Optional.empty());
        when(idempotencyStore.markInProgress(any(), anyString(), anyString())).thenReturn(true);
        when(rateLimiter.tryConsume(any())).thenReturn(RateLimiter.Decision.allow());
        when(templateEngine.render(any())).thenThrow(new IllegalStateException("no channel"));

        NotificationResponse response = service.send(request(null));

        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
        verify(templateEngine, never()).renderAndValidate(any());
        verify(templateEngine).render(any());
    }
}
