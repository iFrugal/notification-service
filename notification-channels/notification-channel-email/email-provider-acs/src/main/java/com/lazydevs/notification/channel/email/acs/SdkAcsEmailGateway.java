package com.lazydevs.notification.channel.email.acs;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.models.EmailMessage;
import com.azure.communication.email.models.EmailSendResult;
import com.azure.communication.email.models.EmailSendStatus;
import com.azure.core.models.ResponseError;
import com.azure.core.util.polling.PollResponse;
import com.azure.core.util.polling.SyncPoller;
import com.lazydevs.notification.channel.email.acs.AcsEmailProperties.SendMode;
import com.lazydevs.notification.channel.email.acs.AcsSendOutcome.Status;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * {@link AcsEmailGateway} backed by the Azure SDK.
 *
 * <p>{@code EmailClient.beginSend} performs the send request eagerly, so once
 * it returns ACS has accepted the message.
 * The {@code SyncPoller} does not expose that first response, so reading the
 * operation id costs one status poll:
 * <ul>
 *   <li>{@link SendMode#WAIT} - {@code waitForCompletion(waitTimeout)}; the final
 *       response carries the id. On timeout one extra {@code poll()} recovers
 *       the id on a best-effort basis.</li>
 *   <li>{@link SendMode#SUBMIT} - exactly one {@code poll()} to read the id, then return.</li>
 * </ul>
 */
@Slf4j
public class SdkAcsEmailGateway implements AcsEmailGateway {

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
        // Throws HttpResponseException etc. when ACS rejects the request.
        SyncPoller<EmailSendResult, EmailSendResult> poller = client.beginSend(message);
        return sendMode == SendMode.SUBMIT ? submitted(poller) : awaitCompletion(poller);
    }

    private static AcsSendOutcome submitted(SyncPoller<EmailSendResult, EmailSendResult> poller) {
        PollResponse<EmailSendResult> response;
        try {
            response = poller.poll();
        } catch (RuntimeException e) {
            // ACS already accepted the message. Reporting a failure here would make
            // the retry executor send it a second time, so report the submission
            // without an id instead.
            log.warn("ACS accepted the email but reading the operation id failed: {}", e.getMessage());
            return AcsSendOutcome.of(null, Status.SUBMITTED);
        }
        return toOutcome(response == null ? null : response.getValue());
    }

    private AcsSendOutcome awaitCompletion(SyncPoller<EmailSendResult, EmailSendResult> poller) {
        PollResponse<EmailSendResult> response;
        try {
            response = poller.waitForCompletion(waitTimeout);
        } catch (RuntimeException e) {
            if (!isTimeout(e)) {
                throw e;
            }
            String operationId = recoverOperationId(poller);
            log.warn("ACS email operation {} did not reach a final status within {}", operationId, waitTimeout);
            return new AcsSendOutcome(operationId, Status.TIMED_OUT, "ACS_WAIT_TIMEOUT",
                    "ACS did not report a final status within " + waitTimeout);
        }
        return toOutcome(response == null ? null : response.getValue());
    }

    private static String recoverOperationId(SyncPoller<EmailSendResult, EmailSendResult> poller) {
        try {
            PollResponse<EmailSendResult> response = poller.poll();
            return response == null || response.getValue() == null ? null : response.getValue().getId();
        } catch (RuntimeException e) {
            log.debug("Could not read the ACS operation id after the wait timeout: {}", e.getMessage());
            return null;
        }
    }

    private static AcsSendOutcome toOutcome(EmailSendResult result) {
        if (result == null) {
            return AcsSendOutcome.of(null, Status.SUBMITTED);
        }
        EmailSendStatus status = result.getStatus();
        if (EmailSendStatus.SUCCEEDED.equals(status)) {
            return AcsSendOutcome.of(result.getId(), Status.SUCCEEDED);
        }
        if (EmailSendStatus.FAILED.equals(status)) {
            ResponseError error = result.getError();
            return new AcsSendOutcome(result.getId(), Status.FAILED,
                    error == null ? null : error.getCode(),
                    error == null ? null : error.getMessage());
        }
        if (EmailSendStatus.CANCELED.equals(status)) {
            return AcsSendOutcome.of(result.getId(), Status.CANCELED);
        }
        // NOT_STARTED, RUNNING, or a status this SDK version does not know yet.
        return AcsSendOutcome.of(result.getId(), Status.SUBMITTED);
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
