package com.lazydevs.notification.channel.email.acs;

/**
 * What {@link AcsEmailGateway#send} observed for one ACS send operation.
 *
 * @param operationId  ACS operation id (the provider message id); {@code null}
 *                     only when ACS accepted the request but the id could not be read
 * @param status       the observed state
 * @param errorCode    ACS error code for {@link Status#FAILED}, or a library code for
 *                     {@link Status#UNCONFIRMED}
 * @param errorMessage ACS error message for {@link Status#FAILED}, or a note on why
 *                     the status is {@link Status#UNCONFIRMED}
 */
public record AcsSendOutcome(String operationId, Status status, String errorCode, String errorMessage) {

    /**
     * Observed state of the operation.
     */
    public enum Status {
        /** ACS reported the message as sent. */
        SUCCEEDED,
        /** ACS accepted the request and has not finished yet ({@code submit} mode). */
        SUBMITTED,
        /** ACS reported a final failure. */
        FAILED,
        /** The operation was canceled. */
        CANCELED,
        /**
         * {@code wait} mode gave up before ACS reached a final state.
         * {@link SdkAcsEmailGateway} reports {@link #UNCONFIRMED} instead since 1.1.1;
         * {@link AcsEmailProvider} treats both the same way.
         */
        TIMED_OUT,
        /**
         * ACS accepted the request, but the final status could not be confirmed:
         * the {@code wait} mode timeout expired or a status poll failed.
         * The message may still be delivered, so it must not be sent again.
         */
        UNCONFIRMED
    }

    static AcsSendOutcome of(String operationId, Status status) {
        return new AcsSendOutcome(operationId, status, null, null);
    }
}
