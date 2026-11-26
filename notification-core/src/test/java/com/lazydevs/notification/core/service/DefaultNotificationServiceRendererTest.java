package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.exception.TemplateNotFoundException;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.model.SmsRecipient;
import com.lazydevs.notification.api.model.WhatsAppRecipient;
import com.lazydevs.notification.api.ratelimit.RateLimiter;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.template.ChannelContentRenderer;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The per-channel {@link ChannelContentRenderer} hook: the renderer of the
 * request's channel wins, every other channel keeps the template engine.
 */
@ExtendWith(MockitoExtension.class)
class DefaultNotificationServiceRendererTest {

    private static final String PHONE = "+15550100001";

    @Mock NotificationProperties properties;
    @Mock ProviderRegistry providerRegistry;
    @Mock NotificationTemplateEngine templateEngine;
    @Mock NotificationAuditService auditService;
    @Mock NotificationProvider provider;
    @Mock IdempotencyStore idempotencyStore;
    @Mock RateLimiter rateLimiter;
    @Mock DeadLetterStore deadLetterStore;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getDefaultTenant()).thenReturn("default");
        lenient().when(providerRegistry.getProvider(anyString(), any(Channel.class), any())).thenReturn(provider);
        lenient().when(provider.getProviderName()).thenReturn("test");
        lenient().when(provider.send(any(), any())).thenReturn(SendResult.success("msg-1"));
    }

    private DefaultNotificationService service(ChannelContentRenderer... renderers) {
        return new DefaultNotificationService(properties, providerRegistry, templateEngine, auditService,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                NotificationServiceExtensions.defaults().withRenderers(Arrays.asList(renderers)));
    }

    private static NotificationRequest request(Channel channel) {
        return NotificationRequest.builder()
                .requestId("req-1")
                .tenantId("acme")
                .notificationType("TEST")
                .channel(channel)
                .recipient(channel == Channel.WHATSAPP
                        ? new WhatsAppRecipient(null, PHONE, null, null, null, null, null)
                        : new SmsRecipient(null, PHONE))
                .build();
    }

    @Test
    void registeredRenderer_rendersItsChannel_andTheTemplateEngineIsNotCalled() {
        RenderedContent structured = new RenderedContent(null, null, null, "TEST", "structured");
        StubRenderer renderer = new StubRenderer(Channel.WHATSAPP, structured);

        NotificationResponse response = service(renderer).send(request(Channel.WHATSAPP));

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(renderer.calls).isEqualTo(1);
        verifyNoInteractions(templateEngine);
        ArgumentCaptor<RenderedContent> sent = ArgumentCaptor.forClass(RenderedContent.class);
        verify(provider).send(any(), sent.capture());
        assertThat(sent.getValue()).isSameAs(structured);
        assertThat(sent.getValue().payload(String.class)).contains("structured");
    }

    @Test
    void otherChannels_keepTheTemplateEngine() {
        RenderedContent text = RenderedContent.text("hello");
        when(templateEngine.render(any())).thenReturn(text);
        StubRenderer renderer = new StubRenderer(Channel.WHATSAPP, RenderedContent.text("unused"));

        NotificationResponse response = service(renderer).send(request(Channel.SMS));

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(renderer.calls).isZero();
        verify(provider).send(any(), same(text));
    }

    @Test
    void withoutRenderers_everyChannelUsesTheTemplateEngine() {
        when(templateEngine.render(any())).thenReturn(RenderedContent.text("hello"));

        NotificationResponse response = new DefaultNotificationService(properties, providerRegistry, templateEngine,
                auditService, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty()).send(request(Channel.WHATSAPP));

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        verify(templateEngine).render(any());
    }

    @Test
    void rendererFailure_isAFailedResponse_andTheProviderIsNotCalled() {
        ChannelContentRenderer failing = new ChannelContentRenderer() {
            @Override
            public Channel channel() {
                return Channel.WHATSAPP;
            }

            @Override
            public RenderedContent render(NotificationRequest request) {
                throw new TemplateNotFoundException("no binding");
            }
        };

        NotificationResponse response = service(failing).send(request(Channel.WHATSAPP));

        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(response.errorCode()).isEqualTo("TEMPLATE_NOT_FOUND");
        verify(provider, never()).send(any(), any());
    }

    @Test
    void twoRenderersForOneChannel_failConstruction_namingTheChannelAndBothClasses() {
        StubRenderer first = new StubRenderer(Channel.WHATSAPP, RenderedContent.text("a"));
        OtherStubRenderer second = new OtherStubRenderer();

        assertThatThrownBy(() -> service(first, second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WHATSAPP")
                .hasMessageContaining(StubRenderer.class.getName())
                .hasMessageContaining(OtherStubRenderer.class.getName());
    }

    @Test
    void nullRendererList_isTreatedAsEmpty() {
        when(templateEngine.render(any())).thenReturn(RenderedContent.text("hello"));
        DefaultNotificationService service = new DefaultNotificationService(properties, providerRegistry,
                templateEngine, auditService, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), NotificationServiceExtensions.defaults().withRenderers(null));

        assertThat(service.send(request(Channel.SMS)).status()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void validateBeforeDispatch_rendererFailure_isThrownBeforeAnythingIsClaimedCountedOrStored() {
        NotificationProperties validating = new NotificationProperties();
        validating.getTemplate().setValidateBeforeDispatch(true);
        TemplateNotFoundException failure = new TemplateNotFoundException("no binding");
        DefaultNotificationService service = new DefaultNotificationService(validating, providerRegistry,
                templateEngine, auditService, Optional.of(idempotencyStore), Optional.of(rateLimiter),
                Optional.empty(), Optional.of(deadLetterStore), Optional.empty(),
                NotificationServiceExtensions.defaults().withRenderers(List.of(new FailingRenderer(failure))));

        assertThatThrownBy(() -> service.send(idempotent(request(Channel.WHATSAPP)))).isSameAs(failure);

        verifyNoInteractions(idempotencyStore, rateLimiter, auditService, deadLetterStore, providerRegistry,
                templateEngine);
    }

    @Test
    void validateBeforeDispatch_rendersWithTheChannelRendererOnce_andDispatchesThatContent() {
        NotificationProperties validating = new NotificationProperties();
        validating.getTemplate().setValidateBeforeDispatch(true);
        RenderedContent content = new RenderedContent(null, null, null, "OTP", "payload");
        StubRenderer renderer = new StubRenderer(Channel.WHATSAPP, content);
        DefaultNotificationService service = new DefaultNotificationService(validating, providerRegistry,
                templateEngine, auditService, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(),
                NotificationServiceExtensions.defaults().withRenderers(List.of(renderer)));

        NotificationResponse response = service.send(request(Channel.WHATSAPP));

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(renderer.calls).isEqualTo(1);
        verify(provider).send(any(), same(content));
        verifyNoInteractions(templateEngine);
    }

    @Test
    void validateBeforeDispatch_channelWithoutRenderer_keepsTheTemplateEngineValidation() {
        NotificationProperties validating = new NotificationProperties();
        validating.getTemplate().setValidateBeforeDispatch(true);
        RenderedContent content = RenderedContent.text("hello");
        when(templateEngine.renderAndValidate(any())).thenReturn(content);
        DefaultNotificationService service = new DefaultNotificationService(validating, providerRegistry,
                templateEngine, auditService, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), NotificationServiceExtensions.defaults()
                        .withRenderers(List.of(new StubRenderer(Channel.WHATSAPP, RenderedContent.text("wa")))));

        assertThat(service.send(request(Channel.SMS)).status()).isEqualTo(NotificationStatus.SENT);

        verify(provider).send(any(), same(content));
        verify(templateEngine, never()).render(any());
    }

    private static NotificationRequest idempotent(NotificationRequest request) {
        return request.toBuilder().idempotencyKey("key-1").build();
    }

    private record FailingRenderer(RuntimeException failure) implements ChannelContentRenderer {
        @Override
        public Channel channel() {
            return Channel.WHATSAPP;
        }

        @Override
        public RenderedContent render(NotificationRequest request) {
            throw failure;
        }
    }

    private static final class StubRenderer implements ChannelContentRenderer {
        private final Channel channel;
        private final RenderedContent content;
        private int calls;

        StubRenderer(Channel channel, RenderedContent content) {
            this.channel = channel;
            this.content = content;
        }

        @Override
        public Channel channel() {
            return channel;
        }

        @Override
        public RenderedContent render(NotificationRequest request) {
            calls++;
            return content;
        }
    }

    private static final class OtherStubRenderer implements ChannelContentRenderer {
        @Override
        public Channel channel() {
            return Channel.WHATSAPP;
        }

        @Override
        public RenderedContent render(NotificationRequest request) {
            return RenderedContent.text("b");
        }
    }
}
