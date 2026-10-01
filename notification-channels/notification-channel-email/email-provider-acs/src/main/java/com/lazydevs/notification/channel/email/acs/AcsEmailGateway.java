package com.lazydevs.notification.channel.email.acs;

import com.azure.communication.email.models.EmailMessage;

import java.util.UUID;

/**
 * Thin seam over the ACS {@code EmailClient} and its {@code SyncPoller}, so
 * {@link AcsEmailProvider} can be unit-tested with a mock.
 *
 * <p>Implementations let the SDK's exceptions propagate unchanged when ACS
 * rejects the request; {@link AcsEmailProvider#classifyAcs(Throwable)} maps them.
 */
public interface AcsEmailGateway {

    /**
     * Submit one message to ACS; ACS assigns the operation id.
     *
     * @param message the SDK message
     * @return the observed outcome, never {@code null}
     */
    AcsSendOutcome send(EmailMessage message);

    /**
     * Submit one message to ACS under a caller-chosen operation id, sent as the
     * {@code Operation-Id} request header so that every attempt for the same
     * logical message uses the same id.
     *
     * <p>{@link AcsEmailProvider} calls this method.
     * The default implementation ignores {@code operationId} and delegates to
     * {@link #send(EmailMessage)}, so gateways written before 1.1.1 keep working.
     *
     * @param message     the SDK message
     * @param operationId the operation id to request, {@code null} to let ACS choose
     * @return the observed outcome, never {@code null}
     * @since 1.1.1
     */
    default AcsSendOutcome send(EmailMessage message, UUID operationId) {
        return send(message);
    }
}
