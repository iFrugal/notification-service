package com.lazydevs.notification.channel.email.acs;

import com.azure.communication.email.models.EmailAddress;
import com.azure.communication.email.models.EmailAttachment;
import com.azure.communication.email.models.EmailMessage;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpResponse;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.ProviderConfigurationException;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.retry.RetryPredicate;
import com.lazydevs.notification.channel.email.acs.AcsEmailProperties.SendMode;
import com.lazydevs.notification.channel.email.acs.AcsSendOutcome.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
        verify(gateway).send(captor.capture(), any());
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
        when(gateway.send(any(), any())).thenReturn(AcsSendOutcome.of("op-123", Status.SUCCEEDED));

        SendResult result = provider.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("op-123");
        assertThat(result.failureType()).isNull();
        assertThat(result.providerMetadata()).containsEntry("acsStatus", "SUCCEEDED");
    }

    @Test
    void submitted_isSuccessWithOperationId() {
        when(gateway.send(any(), any())).thenReturn(AcsSendOutcome.of("op-456", Status.SUBMITTED));

        SendResult result = provider.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("op-456");
        assertThat(result.providerMetadata()).containsEntry("acsStatus", "SUBMITTED");
    }

    @Test
    void failedStatus_mapsToPermanentFailureWithAcsError() {
        when(gateway.send(any(), any())).thenReturn(
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
        when(gateway.send(any(), any())).thenReturn(AcsSendOutcome.of("op-9", Status.FAILED));

        SendResult result = provider.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.errorCode()).isEqualTo("ACS_SEND_FAILED");
        assertThat(result.errorMessage()).isNotBlank();
    }

    @Test
    void unconfirmed_isSuccessWithOperationId_andIsNeverRetried() {
        when(gateway.send(any(), any())).thenReturn(
                new AcsSendOutcome("op-slow", Status.UNCONFIRMED, "ACS_WAIT_TIMEOUT", "no final status"));

        SendResult result = provider.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("op-slow");
        assertThat(result.failureType()).isNull();
        assertThat(result.providerMetadata())
                .containsEntry("acsStatus", "UNCONFIRMED")
                .containsEntry("acsReason", "ACS_WAIT_TIMEOUT");
        assertThat(RetryPredicate.DEFAULT.shouldRetry(result, 1)).isFalse();
    }

    @Test
    void legacyTimedOut_isTreatedAsUnconfirmed() {
        when(gateway.send(any(), any())).thenReturn(
                new AcsSendOutcome("op-slow", Status.TIMED_OUT, "ACS_WAIT_TIMEOUT", "no final status"));

        SendResult result = provider.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("op-slow");
        assertThat(result.providerMetadata()).containsEntry("acsStatus", "UNCONFIRMED");
    }

    @Test
    void sendsTheDerivedOperationId_andReusesItOnRetry() {
        when(gateway.send(any(), any())).thenReturn(AcsSendOutcome.of("op", Status.SUCCEEDED));
        NotificationRequest request = request(to("user@example.com"));
        request.setTenantId("acme");
        request.setRequestId("req-1");

        provider.send(request, htmlAndText());
        provider.send(request, htmlAndText());

        ArgumentCaptor<EmailMessage> message = ArgumentCaptor.forClass(EmailMessage.class);
        ArgumentCaptor<UUID> ids = ArgumentCaptor.forClass(UUID.class);
        verify(gateway, times(2)).send(message.capture(), ids.capture());
        UUID expected = AcsEmailProvider.operationIdFor("acme", "req-1", message.getValue());
        assertThat(ids.getAllValues()).containsExactly(expected, expected);
    }

    @Test
    void withoutRequestId_eachSendGetsAFreshOperationId() {
        when(gateway.send(any(), any())).thenReturn(AcsSendOutcome.of("op", Status.SUCCEEDED));
        NotificationRequest request = request(to("user@example.com"));

        provider.send(request, htmlAndText());
        provider.send(request, htmlAndText());

        ArgumentCaptor<UUID> ids = ArgumentCaptor.forClass(UUID.class);
        verify(gateway, times(2)).send(any(), ids.capture());
        assertThat(ids.getAllValues().get(0)).isNotNull().isNotEqualTo(ids.getAllValues().get(1));
    }

    @Test
    void preSubmissionIoError_isTransient_andCarriesTheOperationId() {
        when(gateway.send(any(), any())).thenThrow(new UncheckedIOException(new IOException("Connection reset")));
        NotificationRequest request = request(to("user@example.com"));
        request.setTenantId("acme");
        request.setRequestId("req-io");

        SendResult result = provider.send(request, htmlAndText());

        ArgumentCaptor<EmailMessage> message = ArgumentCaptor.forClass(EmailMessage.class);
        verify(gateway).send(message.capture(), any());
        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(result.messageId())
                .isEqualTo(AcsEmailProvider.operationIdFor("acme", "req-io", message.getValue()).toString());
    }

    @Test
    void legacyGateway_withoutTheOperationIdMethod_stillWorks() {
        AcsEmailGateway legacy = msg -> AcsSendOutcome.of("acs-chosen", Status.SUCCEEDED);
        AcsEmailProvider withLegacy = new AcsEmailProvider(settings(List.of(), null), legacy);

        SendResult result = withLegacy.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("acs-chosen");
    }

    @Test
    void canceled_mapsToUnknown() {
        when(gateway.send(any(), any())).thenReturn(AcsSendOutcome.of("op-c", Status.CANCELED));

        SendResult result = provider.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.success()).isFalse();
        assertThat(result.messageId()).isEqualTo("op-c");
        assertThat(result.failureType()).isEqualTo(FailureType.UNKNOWN);
    }

    @Test
    void sdkException_isClassified() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(429);
        when(gateway.send(any(), any())).thenThrow(new HttpResponseException("Too many requests", response));

        SendResult result = provider.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("HttpResponseException");
        assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void throttledWithRetryAfterSeconds_putsTheHintInProviderMetadata() {
        HttpResponseException throttled = httpError(429, "7");
        when(gateway.send(any(), any())).thenThrow(throttled);

        SendResult result = provider.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(result.providerMetadata()).containsEntry(SendResult.RETRY_AFTER_METADATA_KEY, "PT7S");
        assertThat(result.retryAfter()).contains(Duration.ofSeconds(7));
        assertThat(result.messageId()).isNotBlank();
    }

    @Test
    void unavailableWithRetryAfterHttpDate_putsTheRemainingDelayInProviderMetadata() {
        String inTwoMinutes = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(120));
        HttpResponseException unavailable = httpError(503, inTwoMinutes);
        when(gateway.send(any(), any())).thenThrow(unavailable);

        SendResult result = provider.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.failureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(result.retryAfter()).hasValueSatisfying(d ->
                assertThat(d).isBetween(Duration.ofSeconds(100), Duration.ofSeconds(120)));
    }

    @Test
    void retryAfterIsIgnored_onPermanentErrors_andWhenAbsentOrMalformed() {
        HttpResponseException badRequest = httpError(400, "7");
        HttpResponseException withoutHeader = httpError(429, null);
        HttpResponseException malformed = httpError(429, "soon");
        when(gateway.send(any(), any()))
                .thenThrow(badRequest)
                .thenThrow(withoutHeader)
                .thenThrow(malformed);

        for (int i = 0; i < 3; i++) {
            SendResult result = provider.send(request(to("user@example.com")), htmlAndText());
            assertThat(result.providerMetadata()).as("attempt %d", i).isNull();
            assertThat(result.retryAfter()).isEmpty();
        }
    }

    private static HttpResponseException httpError(int status, String retryAfter) {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(status);
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) {
            headers.set(HttpHeaderName.RETRY_AFTER, retryAfter);
        }
        when(response.getHeaders()).thenReturn(headers);
        return new HttpResponseException("HTTP " + status, response);
    }

    @Test
    void unauthorized_isPermanentWithCredentialHint() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(401);
        when(gateway.send(any(), any())).thenThrow(new HttpResponseException("Denied", response));

        SendResult result = provider.send(request(to("user@example.com")), htmlAndText());

        assertThat(result.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(result.errorMessage()).contains("Denied").contains("check the ACS credentials");
    }

    @Test
    void mapping_senderSubjectRecipientsAndBodies() {
        when(gateway.send(any(), any())).thenReturn(AcsSendOutcome.of("op", Status.SUCCEEDED));
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
        when(gateway.send(any(), any())).thenReturn(AcsSendOutcome.of("op", Status.SUCCEEDED));

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
        when(gateway.send(any(), any())).thenReturn(AcsSendOutcome.of("op", Status.SUCCEEDED));

        provider.send(request(to("to@example.com")), htmlAndText());

        EmailMessage sent = captureSent();
        assertThat(addresses(sent.getReplyTo())).containsExactly("support@example.com", "ops@example.com");
        assertThat(sent.isUserEngagementTrackingDisabled()).isTrue();
    }

    @Test
    void mapping_recipientReplyToOverridesDefault() {
        provider = new AcsEmailProvider(settings(List.of("support@example.com"), null), gateway);
        when(gateway.send(any(), any())).thenReturn(AcsSendOutcome.of("op", Status.SUCCEEDED));
        EmailRecipient recipient = new EmailRecipient(null, "to@example.com", null, null,
                "owner@example.com", "s");

        provider.send(request(recipient), htmlAndText());

        assertThat(addresses(captureSent().getReplyTo())).containsExactly("owner@example.com");
    }

    @Test
    void mapping_inlineAttachments() {
        when(gateway.send(any(), any())).thenReturn(AcsSendOutcome.of("op", Status.SUCCEEDED));
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
    void withGateway_isReady_andConfigureAndInitAreNoOps() {
        AcsEmailGateway seam = new AcsEmailGateway() {
            @Override
            public AcsSendOutcome send(EmailMessage message) {
                throw new AssertionError("the provider must call send(message, operationId)");
            }

            @Override
            public AcsSendOutcome send(EmailMessage message, UUID operationId) {
                return AcsSendOutcome.of(operationId.toString(), Status.SUBMITTED);
            }
        };
        AcsEmailProvider seamProvider = AcsEmailProvider.withGateway(
                AcsEmailProperties.fromMap(Map.of("connection-string",
                        "endpoint=https://x.communication.azure.com/;accesskey=a2V5", "sender", SENDER)),
                seam);

        seamProvider.configure(Map.of());
        seamProvider.init();
        NotificationRequest request = request(to("user@example.com"));
        request.setTenantId("acme");
        request.setRequestId("req-seam");
        SendResult result = seamProvider.send(request, htmlAndText());

        assertThat(seamProvider.isHealthy()).isTrue();
        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo(AcsEmailProvider.operationIdFor("acme", "req-seam",
                seamProvider.toEmailMessage(request, htmlAndText())).toString());
    }

    @Test
    void withGateway_rejectsNulls() {
        AcsEmailProperties settings = settings(List.of(), null);
        assertThatThrownBy(() -> AcsEmailProvider.withGateway(settings, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("gateway");
        assertThatThrownBy(() -> AcsEmailProvider.withGateway(null, gateway))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("settings");
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
