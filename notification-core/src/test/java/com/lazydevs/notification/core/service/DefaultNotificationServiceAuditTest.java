package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationAudit;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.Recipient;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.model.SmsRecipient;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * DD-07: the audit record carries a masked recipient summary, never the raw address or number.
 */
@ExtendWith(MockitoExtension.class)
class DefaultNotificationServiceAuditTest {

    @Mock NotificationProperties properties;
    @Mock ProviderRegistry providerRegistry;
    @Mock NotificationTemplateEngine templateEngine;
    @Mock NotificationAuditService auditService;
    @Mock NotificationProvider provider;

    private DefaultNotificationService service;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getDefaultTenant()).thenReturn("default");
        service = new DefaultNotificationService(properties, providerRegistry, templateEngine, auditService,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        when(templateEngine.render(any())).thenReturn(new RenderedContent("subj", "body", null, "TEST"));
        when(providerRegistry.getProvider(anyString(), any(Channel.class), any())).thenReturn(provider);
        lenient().when(provider.getProviderName()).thenReturn("test");
        when(provider.send(any(), any())).thenReturn(SendResult.success("msg-1"));
    }

    private static NotificationRequest request(Channel channel, Recipient recipient) {
        return NotificationRequest.builder()
                .requestId("req-1")
                .tenantId("acme")
                .notificationType("TEST")
                .channel(channel)
                .recipient(recipient)
                .build();
    }

    @Test
    void emailRecipientSummary_isMasked() {
        NotificationAudit audit = new NotificationAudit();
        when(auditService.recordReceived(any())).thenReturn(audit);

        service.send(request(Channel.EMAIL, new EmailRecipient(null, "john.doe@example.com",
                List.of("a@example.com", "b@example.com"), null, null, "Private subject")));

        assertThat(audit.getRecipientSummary()).isEqualTo("to=j***@example.com cc=2 bcc=0");
    }

    @Test
    void smsRecipientSummary_isMasked() {
        NotificationAudit audit = new NotificationAudit();
        when(auditService.recordReceived(any())).thenReturn(audit);

        service.send(request(Channel.SMS, new SmsRecipient(null, "+15551234590")));

        assertThat(audit.getRecipientSummary()).isEqualTo("phone=+1***90");
    }

    @Test
    void summarySetByTheAuditImplementation_isKept() {
        NotificationAudit audit = NotificationAudit.builder().recipientSummary("custom").build();
        when(auditService.recordReceived(any())).thenReturn(audit);

        service.send(request(Channel.SMS, new SmsRecipient(null, "+15551234590")));

        assertThat(audit.getRecipientSummary()).isEqualTo("custom");
    }

    @Test
    void disabledAudit_returningNull_isTolerated() {
        when(auditService.recordReceived(any())).thenReturn(null);

        assertThat(service.send(request(Channel.SMS, new SmsRecipient(null, "+15551234590"))).status())
                .isEqualTo(NotificationStatus.SENT);
    }
}
