package com.lazydevs.notification.channel.email.acs;

import com.azure.communication.email.models.EmailAddress;
import com.azure.communication.email.models.EmailAttachment;
import com.azure.communication.email.models.EmailMessage;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpResponse;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.channel.email.acs.AcsEmailProperties.SendMode;
import com.lazydevs.notification.channel.email.acs.AcsSendOutcome.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AcsEmailProvider} against a mocked {@link AcsEmailGateway}.
 */
class AcsEmailProviderTest {

    private static final String SENDER = "DoNotReply@example.com";

    private AcsEmailGateway gateway;
    private AcsEmailProvider provider;

    @BeforeEach
    void setUp() {
        gateway = mock(AcsEmailGateway.class);
        provider = new AcsEmailProvider(settings(List.of(), null), gateway);
    }

    private static AcsEmailProperties settings(List<String> replyTo, Boolean trackingDisabled) {
        return new AcsEmailProperties(null, "endpoint=https://x.communication.azure.com/;accesskey=a2V5",
                null, SENDER, replyTo, SendMode.WAIT, Duration.ofSeconds(60), 0, trackingDisabled);
    }

    private static NotificationRequest request(EmailRecipient recipient) {
        return NotificationRequest.builder()
                .notificationType("WELCOME")
                .channel(Channel.EMAIL)
                .recipient(recipient)
                .build();
    }

    private static EmailRecipient to(String address) {
        return new EmailRecipient(null, address, null, null, null, "Recipient subject");
    }

    private static RenderedContent htmlAndText() {
        return RenderedContent.email("Rendered subject", "<p>Hi</p>", "Hi");
    }

    private EmailMessage captureSent() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(gateway).send(captor.capture());
        return captor.getValue();
    }

    private static List<String> addresses(List<EmailAddress> list) {
        return list == null ? null : list.stream().map(EmailAddress::getAddress).toList();
    }

    @Test
    void identity_isAcsOnEmailChannel() {
        assertThat(provider.getProviderName()).isEqualTo("acs");
        assertThat(provider.getChannel()).isEqualTo(Channel.EMAIL);
    }

    @Test
    void happyPath_returnsOperationIdAsMessageId() {
        when(gateway.send(any())).thenReturn(AcsSendOutcome.of("op-123", Status.SUCCEEDED));

        SendResult result = provider.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("op-123");
        assertThat(result.failureType()).isNull();
        assertThat(result.providerMetadata()).containsEntry("acsStatus", "SUCCEEDED");
    }

    @Test
    void submitted_isSuccessWithOperationId() {
        when(gateway.send(any())).thenReturn(AcsSendOutcome.of("op-456", Status.SUBMITTED));

        SendResult result = provider.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("op-456");
        assertThat(result.providerMetadata()).containsEntry("acsStatus", "SUBMITTED");
    }

    @Test
    void failedStatus_mapsToPermanentFailureWithAcsError() {
        when(gateway.send(any())).thenReturn(
                new AcsSendOutcome("op-9", Status.FAILED, "EmailDroppedAllRecipientsSuppressed", "suppressed"));

        SendResult result = provider.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.success()).isFalse();
        assertThat(result.messageId()).isEqualTo("op-9");
        assertThat(result.errorCode()).isEqualTo("EmailDroppedAllRecipientsSuppressed");
        assertThat(result.errorMessage()).isEqualTo("suppressed");
        assertThat(result.failureType()).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void failedStatusWithoutErrorDetails_usesDefaultCode() {
        when(gateway.send(any())).thenReturn(AcsSendOutcome.of("op-9", Status.FAILED));

        SendResult result = provider.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.errorCode()).isEqualTo("ACS_SEND_FAILED");
        assertThat(result.errorMessage()).isNotBlank();
    }

    @Test
    void timeout_mapsToTransientAndKeepsOperationId() {
        when(gateway.send(any())).thenReturn(
                new AcsSendOutcome("op-slow", Status.TIMED_OUT, "ACS_WAIT_TIMEOUT", "no final status"));

        SendResult result = provider.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.success()).isFalse();
        assertThat(result.messageId()).isEqualTo("op-slow");
        assertThat(result.errorCode()).isEqualTo("ACS_WAIT_TIMEOUT");
        assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void canceled_mapsToUnknown() {
        when(gateway.send(any())).thenReturn(AcsSendOutcome.of("op-c", Status.CANCELED));

        SendResult result = provider.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.success()).isFalse();
        assertThat(result.messageId()).isEqualTo("op-c");
        assertThat(result.failureType()).isEqualTo(FailureType.UNKNOWN);
    }

    @Test
    void sdkException_isClassified() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(429);
        when(gateway.send(any())).thenThrow(new HttpResponseException("Too many requests", response));

        SendResult result = provider.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("HttpResponseException");
        assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void unauthorized_isPermanentWithCredentialHint() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(401);
        when(gateway.send(any())).thenThrow(new HttpResponseException("Denied", response));

        SendResult result = provider.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(result.errorMessage()).contains("Denied").contains("check the ACS credentials");
    }

    @Test
    void mapping_senderSubjectRecipientsAndBodies() {
        when(gateway.send(any())).thenReturn(AcsSendOutcome.of("op", Status.SUCCEEDED));
        EmailRecipient recipient = new EmailRecipient(null, "to@example.com",
                List.of("cc1@example.com", " ", "cc2@example.com"), List.of("bcc@example.com"), null, "ignored");

        provider.send(request(recipient), htmlAndText());

        EmailMessage sent = captureSent();
        assertThat(sent.getSenderAddress()).isEqualTo(SENDER);
        assertThat(sent.getSubject()).isEqualTo("Rendered subject");
        assertThat(addresses(sent.getToRecipients())).containsExactly("to@example.com");
        assertThat(addresses(sent.getCcRecipients())).containsExactly("cc1@example.com", "cc2@example.com");
        assertThat(addresses(sent.getBccRecipients())).containsExactly("bcc@example.com");
        assertThat(sent.getBodyHtml()).isEqualTo("<p>Hi</p>");
        assertThat(sent.getBodyPlainText()).isEqualTo("Hi");
        assertThat(sent.getReplyTo()).isNull();
        assertThat(sent.getAttachments()).isNull();
        assertThat(sent.isUserEngagementTrackingDisabled()).isNull();
    }

    @Test
    void mapping_subjectFallsBackToRecipient_andHtmlOnlyLeavesTextUnset() {
        when(gateway.send(any())).thenReturn(AcsSendOutcome.of("op", Status.SUCCEEDED));

        provider.send(request(to("to@example.com")), RenderedContent.emailHtml(null, "<b>x</b>"));

        EmailMessage sent = captureSent();
        assertThat(sent.getSubject()).isEqualTo("Recipient subject");
        assertThat(sent.getBodyHtml()).isEqualTo("<b>x</b>");
        assertThat(sent.getBodyPlainText()).isNull();
        assertThat(sent.getCcRecipients()).isNull();
        assertThat(sent.getBccRecipients()).isNull();
    }

    @Test
    void mapping_defaultReplyToAndTrackingFlagFromSettings() {
        provider = new AcsEmailProvider(settings(List.of("support@example.com", "ops@example.com"), true), gateway);
        when(gateway.send(any())).thenReturn(AcsSendOutcome.of("op", Status.SUCCEEDED));

        provider.send(request(to("to@example.com")), htmlAndText());

        EmailMessage sent = captureSent();
        assertThat(addresses(sent.getReplyTo())).containsExactly("support@example.com", "ops@example.com");
        assertThat(sent.isUserEngagementTrackingDisabled()).isTrue();
    }

    @Test
    void mapping_recipientReplyToOverridesDefault() {
        provider = new AcsEmailProvider(settings(List.of("support@example.com"), null), gateway);
        when(gateway.send(any())).thenReturn(AcsSendOutcome.of("op", Status.SUCCEEDED));
        EmailRecipient recipient = new EmailRecipient(null, "to@example.com", null, null,
                "owner@example.com", "s");

        provider.send(request(recipient), htmlAndText());

        assertThat(addresses(captureSent().getReplyTo())).containsExactly("owner@example.com");
    }

    @Test
    void mapping_inlineAttachments() {
        when(gateway.send(any())).thenReturn(AcsSendOutcome.of("op", Status.SUCCEEDED));
        NotificationRequest request = request(to("to@example.com"));
        request.setAttachments(List.of(
                new NotificationRequest.Attachment("invoice.pdf", "application/pdf",
                        "%PDF".getBytes(StandardCharsets.UTF_8), null),
                new NotificationRequest.Attachment("blob.bin", null, new byte[]{1, 2}, null)));

        provider.send(request, htmlAndText());

        List<EmailAttachment> attachments = captureSent().getAttachments();
        assertThat(attachments).hasSize(2);
        assertThat(attachments.get(0).getName()).isEqualTo("invoice.pdf");
        assertThat(attachments.get(0).getContentType()).isEqualTo("application/pdf");
        assertThat(attachments.get(0).getContent().toString()).isEqualTo("%PDF");
        assertThat(attachments.get(1).getContentType()).isEqualTo("application/octet-stream");
        assertThat(attachments.get(1).getContent().toBytes()).containsExactly(1, 2);
    }

    @Test
    void urlOnlyAttachment_isPermanentFailure_withoutCallingAcs() {
        NotificationRequest request = request(to("to@example.com"));
        request.setAttachments(List.of(
                new NotificationRequest.Attachment("remote.pdf", "application/pdf", null, "https://x/y.pdf")));

        SendResult result = provider.send(request, htmlAndText());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("ACS_INVALID_MESSAGE");
        assertThat(result.errorMessage()).contains("remote.pdf");
        assertThat(result.failureType()).isEqualTo(FailureType.PERMANENT);
        verifyNoInteractions(gateway);
    }

    @Test
    void missingBody_isPermanentFailure_withoutCallingAcs() {
        SendResult result = provider.send(request(to("to@example.com")),
                new RenderedContent("subject", " ", null, null));

        assertThat(result.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(result.errorCode()).isEqualTo("ACS_INVALID_MESSAGE");
        verifyNoInteractions(gateway);
    }

    @Test
    void reflectiveInstance_rejectsBeanNamedCredential() {
        AcsEmailProvider reflective = new AcsEmailProvider();

        assertThatThrownBy(() -> reflective.configure(Map.of(
                "endpoint", "https://x.communication.azure.com",
                "credential", "myCredential",
                "sender", SENDER)))
                .isInstanceOf(ProviderConfigurationException.class)
                .hasMessageContaining("outside the Spring context");
    }

    @Test
    void reflectiveInstance_withConnectionString_configuresAndInitializes() {
        AcsEmailProvider reflective = new AcsEmailProvider();
        reflective.configure(Map.of(
                "connection-string", "endpoint=https://x.communication.azure.com/;accesskey=a2V5",
                "sender", SENDER,
                "send-mode", "submit"));
        reflective.init();

        assertThat(reflective.gateway()).isInstanceOf(SdkAcsEmailGateway.class);
        assertThat(reflective.settings().sendMode()).isEqualTo(SendMode.SUBMIT);
        assertThat(reflective.isHealthy()).isTrue();
    }

    @Test
    void reconfigure_rebuildsTheGatewayOnInit() {
        AcsEmailProvider reflective = new AcsEmailProvider();
        reflective.configure(Map.of(
                "connection-string", "endpoint=https://x.communication.azure.com/;accesskey=a2V5",
                "sender", SENDER));
        reflective.init();
        AcsEmailGateway first = reflective.gateway();

        reflective.configure(Map.of(
                "connection-string", "endpoint=https://y.communication.azure.com/;accesskey=a2V5",
                "sender", SENDER));
        assertThat(reflective.isHealthy()).isFalse();
        reflective.init();

        assertThat(reflective.gateway()).isNotNull().isNotSameAs(first);
    }

    @Test
    void lifecycleMisuse_failsClearly() {
        AcsEmailProvider fresh = new AcsEmailProvider();
        assertThat(fresh.isHealthy()).isFalse();
        assertThatThrownBy(fresh::init).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configure");
        assertThatThrownBy(() -> fresh.send(request(to("to@example.com")), htmlAndText()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");
    }
}
