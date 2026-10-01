package com.lazydevs.notification.channel.email.acs;

import com.azure.communication.email.models.EmailMessage;

/**
 * Thin seam over the ACS {@code EmailClient} and its {@code SyncPoller}, so
 * {@link AcsEmailProvider} can be unit-tested with a mock.
 *
 * <p>Implementations let the SDK's exceptions propagate unchanged when ACS
 * rejects the request; {@link AcsEmailProvider#classifyAcs(Throwable)} maps them.
 */
public interface AcsEmailGateway {

    /**
     * Submit one message to ACS.
     *
     * @param message the SDK message
     * @return the observed outcome, never {@code null}
     */
    AcsSendOutcome send(EmailMessage message);
}
