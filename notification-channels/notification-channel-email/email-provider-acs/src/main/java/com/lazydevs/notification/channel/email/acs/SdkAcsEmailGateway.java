package com.lazydevs.notification.channel.email.acs;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.models.EmailMessage;
import com.azure.communication.email.models.EmailSendResult;
import com.azure.communication.email.models.EmailSendStatus;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.policy.AddHeadersFromContextPolicy;
import com.azure.core.models.ResponseError;
import com.azure.core.util.Context;
import com.azure.core.util.polling.PollResponse;
import com.azure.core.util.polling.SyncPoller;
import com.lazydevs.notification.api.util.PiiMasking;
import com.lazydevs.notification.channel.email.acs.AcsEmailProperties.SendMode;
import com.lazydevs.notification.channel.email.acs.AcsSendOutcome.Status;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * {@link AcsEmailGateway} backed by the Azure SDK.
 *
 * <p>{@code EmailClient.beginSend} performs the send request eagerly, so once
 * it returns ACS has accepted the message.
 * {@link #send(EmailMessage, UUID)} sends the caller's operation id as the
 * {@code Operation-Id} request header (through azure-core's
 * {@link AddHeadersFromContextPolicy}, which runs before the SDK retry policy,
 * so SDK retries carry the same id):
 * <ul>
 *   <li>{@link SendMode#SUBMIT} - returns the supplied id right after acceptance,
 *       without a status call.</li>
 *   <li>{@link SendMode#WAIT} - {@code waitForCompletion(waitTimeout)}; the final
 *       response carries the id. A timeout or a failed status poll is reported as
 *       {@link Status#UNCONFIRMED} with the id, never as an error, because the
 *       message was already accepted.</li>
 * </ul>
 *
 * <p>{@link #send(EmailMessage)} lets ACS choose the id; reading it then costs
 * one status poll in {@code SUBMIT} mode, and after a {@code WAIT} timeout or
 * poll error one extra poll recovers it on a best-effort basis.
 */
@Slf4j
public class SdkAcsEmailGateway implements AcsEmailGateway {

    /** ACS request header naming the long-running send operation. */
    static final HttpHeaderName OPERATION_ID = HttpHeaderName.fromString("Operation-Id");

    private static final String CODE_WAIT_TIMEOUT = "ACS_WAIT_TIMEOUT";
    private static final String CODE_POLL_FAILED = "ACS_POLL_FAILED";

    private final EmailClient client;
    private final SendMode sendMode;
    private final Duration waitTimeout;

    /**
     * @param client      the SDK client
     * @param sendMode    wait for a final status or return after submission
     * @param waitTimeout upper bound for {@link SendMode#WAIT}
     */
    public SdkAcsEmailGateway(EmailClient client, SendMode sendMode, Duration waitTimeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.sendMode = Objects.requireNonNull(sendMode, "sendMode");
        this.waitTimeout = Objects.requireNonNull(waitTimeout, "waitTimeout");
    }

    @Override
    public AcsSendOutcome send(EmailMessage message) {
        return send(message, null);
    }

    @Override
    public AcsSendOutcome send(EmailMessage message, UUID operationId) {
        // Throws HttpResponseException etc. when ACS rejects the request.
        SyncPoller<EmailSendResult, EmailSendResult> poller = operationId == null
                ? client.beginSend(message)
                : client.beginSend(message, operationIdContext(operationId));
        String suppliedId = operationId == null ? null : operationId.toString();
        return sendMode == SendMode.SUBMIT ? submitted(poller, suppliedId) : awaitCompletion(poller, suppliedId);
    }

    /**
     * A {@link Context} that makes azure-core add {@code Operation-Id: <operationId>}
     * to the send request (and harmlessly to the status polls that reuse the context).
     */
    static Context operationIdContext(UUID operationId) {
        return new Context(AddHeadersFromContextPolicy.AZURE_REQUEST_HTTP_HEADERS_KEY,
                new HttpHeaders().set(OPERATION_ID, operationId.toString()));
    }

    private static AcsSendOutcome submitted(SyncPoller<EmailSendResult, EmailSendResult> poller,
                                            String suppliedId) {
        if (suppliedId != null) {
            // The id is ours, so no status call is needed to learn it.
            return AcsSendOutcome.of(suppliedId, Status.SUBMITTED);
        }
        PollResponse<EmailSendResult> response;
        try {
            response = poller.poll();
        } catch (RuntimeException e) {
            // ACS already accepted the message. Reporting a failure here would make
            // the retry executor send it a second time, so report the submission
            // without an id instead.
            log.warn("ACS accepted the email but reading the operation id failed: {}",
                    PiiMasking.redact(e.getMessage()));
            return AcsSendOutcome.of(null, Status.SUBMITTED);
        }
        return toOutcome(response == null ? null : response.getValue(), null);
    }

    private AcsSendOutcome awaitCompletion(SyncPoller<EmailSendResult, EmailSendResult> poller,
                                           String suppliedId) {
        PollResponse<EmailSendResult> response;
        try {
            response = poller.waitForCompletion(waitTimeout);
        } catch (RuntimeException e) {
            // beginSend returned, so ACS accepted the message: whatever went wrong
            // while waiting, resending could deliver it twice.
            String operationId = suppliedId != null ? suppliedId : recoverOperationId(poller);
            if (isTimeout(e)) {
                log.warn("ACS email operation {} did not reach a final status within {}", operationId, waitTimeout);
                return new AcsSendOutcome(operationId, Status.UNCONFIRMED, CODE_WAIT_TIMEOUT,
                        "ACS did not report a final status within " + waitTimeout);
            }
            String error = PiiMasking.redact(e.getMessage());
            log.warn("ACS accepted email operation {} but reading its status failed: {}", operationId, error);
            return new AcsSendOutcome(operationId, Status.UNCONFIRMED, CODE_POLL_FAILED,
                    "ACS accepted the message but reading its status failed: " + error);
        }
        return toOutcome(response == null ? null : response.getValue(), suppliedId);
    }

    private static String recoverOperationId(SyncPoller<EmailSendResult, EmailSendResult> poller) {
        try {
            PollResponse<EmailSendResult> response = poller.poll();
            return response == null || response.getValue() == null ? null : response.getValue().getId();
        } catch (RuntimeException e) {
            log.debug("Could not read the ACS operation id after the wait failed: {}",
                    PiiMasking.redact(e.getMessage()));
            return null;
        }
    }

    private static AcsSendOutcome toOutcome(EmailSendResult result, String suppliedId) {
        if (result == null) {
            return AcsSendOutcome.of(suppliedId, Status.SUBMITTED);
        }
        String id = result.getId() != null ? result.getId() : suppliedId;
        EmailSendStatus status = result.getStatus();
        if (EmailSendStatus.SUCCEEDED.equals(status)) {
            return AcsSendOutcome.of(id, Status.SUCCEEDED);
        }
        if (EmailSendStatus.FAILED.equals(status)) {
            ResponseError error = result.getError();
            return new AcsSendOutcome(id, Status.FAILED,
                    error == null ? null : error.getCode(),
                    error == null ? null : error.getMessage());
        }
        if (EmailSendStatus.CANCELED.equals(status)) {
            return AcsSendOutcome.of(id, Status.CANCELED);
        }
        // NOT_STARTED, RUNNING, or a status this SDK version does not know yet.
        return AcsSendOutcome.of(id, Status.SUBMITTED);
    }

    /**
     * {@code SyncPoller.waitForCompletion(Duration)} signals a timeout with a
     * {@link TimeoutException}, wrapped by Reactor's {@code block()}.
     */
    static boolean isTimeout(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
