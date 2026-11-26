package com.lazydevs.notification.core.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;
import com.lazydevs.notification.api.guard.RecipientGuard;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.NotificationView;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.util.MessageScrubber;
import com.lazydevs.notification.core.audit.InMemoryNotificationAuditStore;
import com.lazydevs.notification.core.audit.StoreBackedAuditService;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.guard.ConfiguredRecipientGuard;
import com.lazydevs.notification.core.idempotency.CaffeineIdempotencyStore;
import com.lazydevs.notification.core.idempotency.RequestFingerprint;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DD-34: the recipient guard inside {@link DefaultNotificationService#send}.
 */
@ExtendWith(MockitoExtension.class)
class DefaultNotificationServiceRecipientGuardTest {

    private static final String REDIRECT = "qa-inbox@example.com";

    @Mock ProviderRegistry providerRegistry;
    @Mock NotificationTemplateEngine templateEngine;
    @Mock NotificationProvider provider;
    @Mock DeadLetterStore deadLetterStore;

    private NotificationProperties properties;
    private CaffeineIdempotencyStore idempotency;
    private StoreBackedAuditService audit;
    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        idempotency = new CaffeineIdempotencyStore(properties);
        audit = new StoreBackedAuditService(
                new InMemoryNotificationAuditStore(100, Duration.ofDays(1), Clock.systemUTC()), "default",
                Clock.systemUTC());
        lenient().when(templateEngine.render(any())).thenReturn(new RenderedContent("Welcome", "b", null, "W"));
        lenient().when(providerRegistry.getProvider(anyString(), any(Channel.class), any())).thenReturn(provider);
        lenient().when(provider.getProviderName()).thenReturn("smtp");
        serviceLogger = (Logger) LoggerFactory.getLogger(DefaultNotificationService.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        serviceLogger.detachAppender(logs);
    }

    @Test
    void redirect_sendsToTheRedirectAddress_withASubjectPrefix_andPersistsNoOriginal() {
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-1"));
        NotificationRequest request = request("customer@customer.io", List.of("ok@example.com"));
        String originalFingerprint = RequestFingerprint.of(request("customer@customer.io", List.of("ok@example.com")));

        NotificationResponse response = service(configuredGuard()).send(request);

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        ArgumentCaptor<NotificationRequest> sentRequest = ArgumentCaptor.forClass(NotificationRequest.class);
        ArgumentCaptor<RenderedContent> sentContent = ArgumentCaptor.forClass(RenderedContent.class);
        verify(provider).send(sentRequest.capture(), sentContent.capture());
        EmailRecipient sentTo = (EmailRecipient) sentRequest.getValue().getRecipient();
        assertThat(sentTo.to()).isEqualTo(REDIRECT);
        assertThat(sentTo.cc()).containsExactly("ok@example.com");
        assertThat(sentContent.getValue().subject()).isEqualTo("[redirected: customer@customer.io] Welcome");
        // The caller's request object is not changed.
        assertThat(((EmailRecipient) request.getRecipient()).to()).isEqualTo("customer@customer.io");
        // The idempotency fingerprint is the original request's.
        assertThat(idempotency.findExisting(new IdempotencyKey("acme", null, "k-1")).orElseThrow().fingerprint())
                .isEqualTo(originalFingerprint);
        // The audit record and the view never see the original address.
        NotificationView view = service(configuredGuard()).find("acme", request.getRequestId()).orElseThrow();
        assertThat(view.recipientCount()).isEqualTo(2);
        assertThat(view.toString()).doesNotContain("customer@customer.io").doesNotContain("customer.io");
        assertThat(audit.findByRequestId("acme", request.getRequestId()).orElseThrow().toString())
                .doesNotContain("customer");
        // One INFO line with counts, never the addresses.
        List<String> guardLines = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("Recipient guard changed")).toList();
        assertThat(guardLines).hasSize(1);
        assertThat(guardLines.getFirst()).contains("redirected=1").contains("dropped=0")
                .doesNotContain("customer");
    }

    @Test
    void blockedSend_failsPermanentWithoutReachingTheProvider() {
        configuredGuard(); // fills the email rule
        properties.getRecipientGuard().getChannels().get("email").setRedirectTo(null);
        ConfiguredRecipientGuard dropping = new ConfiguredRecipientGuard(properties.getRecipientGuard());

        NotificationResponse response = new DefaultNotificationService(properties, providerRegistry, templateEngine,
                audit, Optional.of(idempotency), Optional.empty(), Optional.empty(), Optional.of(deadLetterStore),
                Optional.empty(), extensions(dropping)).send(request("customer@customer.io", null));

        assertThat(response.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(response.errorCode()).isEqualTo(RecipientGuard.BLOCKED_ERROR_CODE);
        assertThat(response.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(response.errorMessage()).doesNotContain("customer");
        verify(provider, never()).send(any(), any());
        verify(deadLetterStore).add(any());
        NotificationView view = service(dropping).find("acme", response.requestId()).orElseThrow();
        assertThat(view.errorCode()).isEqualTo("RECIPIENT_BLOCKED");
        assertThat(view.recipientCount()).isNull();
    }

    @Test
    void noRecipient_isNotGuarded() {
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-2"));
        NotificationRequest request = request("x@example.com", null);
        request.setRecipient(null);
        request.setIdempotencyKey(null);

        NotificationResponse response = service(configuredGuard()).send(request);

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void allowedRecipients_areSentUnchanged_withoutAPrefixOrLogLine() {
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-3"));
        NotificationRequest request = request("fine@example.com", null);

        service(configuredGuard()).send(request);

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        ArgumentCaptor<RenderedContent> content = ArgumentCaptor.forClass(RenderedContent.class);
        verify(provider).send(sent.capture(), content.capture());
        assertThat(sent.getValue()).isSameAs(request);
        assertThat(content.getValue().subject()).isEqualTo("Welcome");
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().startsWith("Recipient guard changed"));
    }

    @Test
    void subjectPrefix_fallsBackToTheRecipientSubject_whenTheTemplateHasNone() {
        when(templateEngine.render(any())).thenReturn(new RenderedContent(null, "b", null, "W"));
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-4"));
        NotificationRequest request = request("customer@customer.io", null);
        request.setRecipient(new EmailRecipient(null, "customer@customer.io", null, null, null, "Your order"));

        service(configuredGuard()).send(request);

        ArgumentCaptor<RenderedContent> content = ArgumentCaptor.forClass(RenderedContent.class);
        verify(provider).send(any(), content.capture());
        assertThat(content.getValue().subject()).isEqualTo("[redirected: customer@customer.io] Your order");
    }

    @Test
    void customGuard_isUsed_andAGuardRunsWithRetriesToo() {
        RecipientGuard onlyTests = request -> new RecipientGuard.Decision(
                new EmailRecipient(null, "test@example.com", null, null, null, null), List.of(), 1, null);
        when(templateEngine.render(any())).thenReturn(new RenderedContent(null, "b", null, "W"));
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-5"));

        service(onlyTests).send(request("customer@customer.io", null));

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        ArgumentCaptor<RenderedContent> content = ArgumentCaptor.forClass(RenderedContent.class);
        verify(provider).send(sent.capture(), content.capture());
        assertThat(((EmailRecipient) sent.getValue().getRecipient()).to()).isEqualTo("test@example.com");
        assertThat(content.getValue().subject()).isNull();
    }

    @Test
    void validateBeforeDispatch_rendersOnce_andTheGuardPrefixesThePreRenderedSubject() {
        properties.getTemplate().setValidateBeforeDispatch(true);
        when(templateEngine.renderAndValidate(any())).thenReturn(new RenderedContent("Welcome", "b", null, "W"));
        when(provider.send(any(), any())).thenReturn(SendResult.success("m-1"));

        NotificationResponse response = service(configuredGuard()).send(request("customer@customer.io", null));

        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
        verify(templateEngine).renderAndValidate(any());
        verify(templateEngine, never()).render(any());
        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        ArgumentCaptor<RenderedContent> content = ArgumentCaptor.forClass(RenderedContent.class);
        verify(provider).send(sent.capture(), content.capture());
        assertThat(((EmailRecipient) sent.getValue().getRecipient()).to()).isEqualTo(REDIRECT);
        assertThat(content.getValue().subject()).isEqualTo("[redirected: customer@customer.io] Welcome");
    }

    private DefaultNotificationService service(RecipientGuard guard) {
        return new DefaultNotificationService(properties, providerRegistry, templateEngine, audit,
                Optional.of(idempotency), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                extensions(guard));
    }

    private static NotificationServiceExtensions extensions(RecipientGuard guard) {
        return new NotificationServiceExtensions(MessageScrubber.placeholders(), Clock.systemUTC(), null, guard, null);
    }

    private ConfiguredRecipientGuard configuredGuard() {
        NotificationProperties.RecipientGuardProperties config = properties.getRecipientGuard();
        config.setEnabled(true);
        config.getChannels().computeIfAbsent("email", k -> {
            NotificationProperties.RecipientGuardChannel rule = new NotificationProperties.RecipientGuardChannel();
            rule.setAllowed(new java.util.ArrayList<>(List.of("@example.com")));
            rule.setRedirectTo(REDIRECT);
            return rule;
        });
        return new ConfiguredRecipientGuard(config);
    }

    private static NotificationRequest request(String to, List<String> cc) {
        return NotificationRequest.builder()
                .requestId("req-" + System.nanoTime())
                .tenantId("acme")
                .notificationType("WELCOME")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, to, cc, null, null, null))
                .templateData(Map.of("name", "Ada"))
                .idempotencyKey("k-1")
                .build();
    }
}
