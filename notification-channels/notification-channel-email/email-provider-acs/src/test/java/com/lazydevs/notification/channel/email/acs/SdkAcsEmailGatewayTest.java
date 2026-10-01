package com.lazydevs.notification.channel.email.acs;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.models.EmailMessage;
import com.azure.communication.email.models.EmailSendResult;
import com.azure.communication.email.models.EmailSendStatus;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.policy.AddHeadersFromContextPolicy;
import com.azure.core.models.ResponseError;
import com.azure.core.util.Context;
import com.azure.core.util.polling.LongRunningOperationStatus;
import com.azure.core.util.polling.PollResponse;
import com.azure.core.util.polling.SyncPoller;
import com.lazydevs.notification.channel.email.acs.AcsEmailProperties.SendMode;
import com.lazydevs.notification.channel.email.acs.AcsSendOutcome.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.Exceptions;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SdkAcsEmailGateway} with a mocked {@link EmailClient} and {@link SyncPoller}.
 */
class SdkAcsEmailGatewayTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(7);
    private static final UUID OPERATION_ID = UUID.fromString("0b0e8f0c-3c5a-3d47-9a52-6f1d2c1f4e11");

    private EmailClient client;
    private SyncPoller<EmailSendResult, EmailSendResult> poller;
    private final EmailMessage message = new EmailMessage();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(EmailClient.class);
        poller = mock(SyncPoller.class);
        when(client.beginSend(message)).thenReturn(poller);
        when(client.beginSend(eq(message), any(Context.class))).thenReturn(poller);
    }

    private static void throwTimeout(SyncPoller<EmailSendResult, EmailSendResult> poller) {
        // SyncOverAsyncPoller signals the timeout through Reactor's block(), which
        // wraps the checked TimeoutException. thenAnswer, not thenThrow: Mockito's
        // thenThrow calls fillInStackTrace(), which ReactiveException redirects to its cause.
        when(poller.waitForCompletion(TIMEOUT)).thenAnswer(inv -> {
            throw Exceptions.propagate(new TimeoutException("slow"));
        });
    }

    private static PollResponse<EmailSendResult> response(LongRunningOperationStatus lro, String id,
                                                          EmailSendStatus status, ResponseError error) {
        return new PollResponse<>(lro, new EmailSendResult(id, status, error));
    }

    @Test
    void waitMode_succeeded_returnsIdFromFinalResponse_withoutExtraPoll() {
        when(poller.waitForCompletion(TIMEOUT)).thenReturn(
                response(LongRunningOperationStatus.SUCCESSFULLY_COMPLETED, "op-1", EmailSendStatus.SUCCEEDED, null));

        AcsSendOutcome outcome = new SdkAcsEmailGateway(client, SendMode.WAIT, TIMEOUT).send(message);

        assertThat(outcome).isEqualTo(AcsSendOutcome.of("op-1", Status.SUCCEEDED));
        verify(poller).waitForCompletion(TIMEOUT);
        verify(poller, never()).poll();
    }

    @Test
    void waitMode_failed_carriesAcsError() {
        when(poller.waitForCompletion(TIMEOUT)).thenReturn(response(LongRunningOperationStatus.FAILED, "op-2",
                EmailSendStatus.FAILED, new ResponseError("InvalidRecipient", "bad address")));

        AcsSendOutcome outcome = new SdkAcsEmailGateway(client, SendMode.WAIT, TIMEOUT).send(message);

        assertThat(outcome).isEqualTo(new AcsSendOutcome("op-2", Status.FAILED, "InvalidRecipient", "bad address"));
    }

    @Test
    void waitMode_canceled() {
        when(poller.waitForCompletion(TIMEOUT)).thenReturn(response(LongRunningOperationStatus.USER_CANCELLED,
                "op-3", EmailSendStatus.CANCELED, null));

        assertThat(new SdkAcsEmailGateway(client, SendMode.WAIT, TIMEOUT).send(message).status())
                .isEqualTo(Status.CANCELED);
    }

    @Test
    void waitMode_timeout_recoversOperationIdWithOnePoll() {
        throwTimeout(poller);
        when(poller.poll()).thenReturn(
                response(LongRunningOperationStatus.IN_PROGRESS, "op-slow", EmailSendStatus.RUNNING, null));

        AcsSendOutcome outcome = new SdkAcsEmailGateway(client, SendMode.WAIT, TIMEOUT).send(message);

        assertThat(outcome.status()).isEqualTo(Status.UNCONFIRMED);
        assertThat(outcome.operationId()).isEqualTo("op-slow");
        assertThat(outcome.errorCode()).isEqualTo("ACS_WAIT_TIMEOUT");
        assertThat(outcome.errorMessage()).contains("PT7S");
        verify(poller, times(1)).poll();
    }

    @Test
    void waitMode_timeout_whenRecoveryPollFails_isStillUnconfirmed() {
        throwTimeout(poller);
        when(poller.poll()).thenThrow(new IllegalStateException("network down"));

        AcsSendOutcome outcome = new SdkAcsEmailGateway(client, SendMode.WAIT, TIMEOUT).send(message);

        assertThat(outcome.status()).isEqualTo(Status.UNCONFIRMED);
        assertThat(outcome.operationId()).isNull();
    }

    @Test
    void waitMode_nonTimeoutPollingError_isUnconfirmed_notRethrown() {
        // beginSend returned, so ACS accepted the message: rethrowing would make the
        // retry executor send it again.
        when(poller.waitForCompletion(TIMEOUT)).thenThrow(new HttpResponseException("boom", null));
        when(poller.poll()).thenReturn(
                response(LongRunningOperationStatus.IN_PROGRESS, "op-acs", EmailSendStatus.RUNNING, null));

        AcsSendOutcome outcome = new SdkAcsEmailGateway(client, SendMode.WAIT, TIMEOUT).send(message);

        assertThat(outcome.status()).isEqualTo(Status.UNCONFIRMED);
        assertThat(outcome.operationId()).isEqualTo("op-acs");
        assertThat(outcome.errorCode()).isEqualTo("ACS_POLL_FAILED");
        assertThat(outcome.errorMessage()).contains("boom");
    }

    // ---------- caller-chosen operation id ----------

    @Test
    void withOperationId_beginSendCarriesTheOperationIdHeaderInTheContext() {
        when(poller.waitForCompletion(TIMEOUT)).thenReturn(response(LongRunningOperationStatus.SUCCESSFULLY_COMPLETED,
                OPERATION_ID.toString(), EmailSendStatus.SUCCEEDED, null));

        new SdkAcsEmailGateway(client, SendMode.WAIT, TIMEOUT).send(message, OPERATION_ID);

        ArgumentCaptor<Context> context = ArgumentCaptor.forClass(Context.class);
        verify(client).beginSend(eq(message), context.capture());
        verify(client, never()).beginSend(message);
        Object headers = context.getValue().getData(AddHeadersFromContextPolicy.AZURE_REQUEST_HTTP_HEADERS_KEY)
                .orElseThrow();
        assertThat(((HttpHeaders) headers).getValue(SdkAcsEmailGateway.OPERATION_ID))
                .isEqualTo(OPERATION_ID.toString());
    }

    @Test
    void withOperationId_submitMode_returnsTheIdWithoutAnyStatusCall() {
        AcsSendOutcome outcome = new SdkAcsEmailGateway(client, SendMode.SUBMIT, TIMEOUT).send(message, OPERATION_ID);

        assertThat(outcome).isEqualTo(AcsSendOutcome.of(OPERATION_ID.toString(), Status.SUBMITTED));
        verify(poller, never()).poll();
        verify(poller, never()).waitForCompletion(any());
    }

    @Test
    void withOperationId_waitTimeout_isUnconfirmedWithTheId_withoutRecoveryPoll() {
        throwTimeout(poller);

        AcsSendOutcome outcome = new SdkAcsEmailGateway(client, SendMode.WAIT, TIMEOUT).send(message, OPERATION_ID);

        assertThat(outcome.status()).isEqualTo(Status.UNCONFIRMED);
        assertThat(outcome.operationId()).isEqualTo(OPERATION_ID.toString());
        assertThat(outcome.errorCode()).isEqualTo("ACS_WAIT_TIMEOUT");
        verify(poller, never()).poll();
    }

    @Test
    void withOperationId_pollError_isUnconfirmedWithTheId() {
        when(poller.waitForCompletion(TIMEOUT)).thenThrow(
                new IllegalStateException("status GET failed for john@example.com"));

        AcsSendOutcome outcome = new SdkAcsEmailGateway(client, SendMode.WAIT, TIMEOUT).send(message, OPERATION_ID);

        assertThat(outcome.status()).isEqualTo(Status.UNCONFIRMED);
        assertThat(outcome.operationId()).isEqualTo(OPERATION_ID.toString());
        assertThat(outcome.errorMessage()).contains("j***@example.com").doesNotContain("john@example.com");
        verify(poller, never()).poll();
    }

    @Test
    void withOperationId_finalResponseWithoutId_fallsBackToTheSuppliedId() {
        when(poller.waitForCompletion(TIMEOUT)).thenReturn(response(LongRunningOperationStatus.SUCCESSFULLY_COMPLETED,
                null, EmailSendStatus.SUCCEEDED, null));

        AcsSendOutcome outcome = new SdkAcsEmailGateway(client, SendMode.WAIT, TIMEOUT).send(message, OPERATION_ID);

        assertThat(outcome).isEqualTo(AcsSendOutcome.of(OPERATION_ID.toString(), Status.SUCCEEDED));
    }

    @Test
    void withOperationId_rejectedSend_propagatesSdkException() {
        HttpResponseException rejected = new HttpResponseException("rejected", null);
        when(client.beginSend(eq(message), any(Context.class))).thenThrow(rejected);

        SdkAcsEmailGateway gateway = new SdkAcsEmailGateway(client, SendMode.WAIT, TIMEOUT);
        assertThatThrownBy(() -> gateway.send(message, OPERATION_ID)).isSameAs(rejected);
    }

    @Test
    void legacySubmitMode_pollsExactlyOnce_andReturnsOperationId() {
        when(poller.poll()).thenReturn(
                response(LongRunningOperationStatus.IN_PROGRESS, "op-sub", EmailSendStatus.RUNNING, null));

        AcsSendOutcome outcome = new SdkAcsEmailGateway(client, SendMode.SUBMIT, TIMEOUT).send(message);

        assertThat(outcome).isEqualTo(AcsSendOutcome.of("op-sub", Status.SUBMITTED));
        verify(poller, times(1)).poll();
        verify(poller, never()).waitForCompletion(any());
        verify(poller, never()).waitForCompletion();
    }

    @Test
    void submitMode_alreadyFinished_reportsFinalStatus() {
        when(poller.poll()).thenReturn(response(LongRunningOperationStatus.FAILED, "op-f",
                EmailSendStatus.FAILED, new ResponseError("Rejected", "no")));

        AcsSendOutcome outcome = new SdkAcsEmailGateway(client, SendMode.SUBMIT, TIMEOUT).send(message);

        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("Rejected");
    }

    @Test
    void submitMode_pollFailure_stillReportsSubmission() {
        // beginSend returned, so ACS accepted the message; failing here would cause a duplicate on retry.
        when(poller.poll()).thenThrow(new IllegalStateException("network down"));

        AcsSendOutcome outcome = new SdkAcsEmailGateway(client, SendMode.SUBMIT, TIMEOUT).send(message);

        assertThat(outcome).isEqualTo(AcsSendOutcome.of(null, Status.SUBMITTED));
    }

    @Test
    void rejectedSend_propagatesSdkException() {
        HttpResponseException rejected = new HttpResponseException("rejected", null);
        when(client.beginSend(message)).thenThrow(rejected);

        SdkAcsEmailGateway gateway = new SdkAcsEmailGateway(client, SendMode.SUBMIT, TIMEOUT);
        assertThatThrownBy(() -> gateway.send(message)).isSameAs(rejected);
    }

    @Test
    void isTimeout_walksCauseChain() {
        assertThat(SdkAcsEmailGateway.isTimeout(new RuntimeException(new TimeoutException()))).isTrue();
        assertThat(SdkAcsEmailGateway.isTimeout(new RuntimeException("other"))).isFalse();
    }
}
