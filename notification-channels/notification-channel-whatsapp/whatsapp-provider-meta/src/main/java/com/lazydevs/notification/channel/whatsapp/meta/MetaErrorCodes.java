package com.lazydevs.notification.channel.whatsapp.meta;

/**
 * The {@code errorCode} values of a failed
 * {@link com.lazydevs.notification.api.model.SendResult} from
 * {@link MetaWhatsAppProvider}: a documented contract (DD-35), so a caller can
 * decide without parsing messages.
 *
 * <p>"Nothing was sent, try again later": {@link #CONNECT_FAILED},
 * {@link #THROTTLED}, {@link #PAIR_RATE_LIMITED}, {@link #PROVIDER_UNAVAILABLE},
 * {@link #CREDENTIALS_UNAVAILABLE} and {@link #CREDENTIAL_REJECTED} (the last
 * one only after the credential is fixed). "May have been sent":
 * {@link #NO_CONFIRMATION}. Everything else is final for this message.
 *
 * @since 1.3.0
 */
public final class MetaErrorCodes {

    /** TRANSIENT: no connection to Meta (DNS, refused, connect timeout); nothing was sent. */
    public static final String CONNECT_FAILED = "CONNECT_FAILED";

    /**
     * AMBIGUOUS: the request went out but no usable answer came back (read
     * timeout, connection lost, interrupted, 2xx without a message id). Not
     * retried by default: a resend could deliver the message twice.
     */
    public static final String NO_CONFIRMATION = "NO_CONFIRMATION";

    /** TRANSIENT: throughput limit (HTTP 429, codes 4, 80007, 130429); honours Retry-After. */
    public static final String THROTTLED = "THROTTLED";

    /**
     * TRANSIENT: too many messages to the same recipient too fast (code 131056);
     * retry after the larger of Retry-After and {@code pair-rate-limit-retry-after}.
     */
    public static final String PAIR_RATE_LIMITED = "PAIR_RATE_LIMITED";

    /** TRANSIENT: Meta is failing (HTTP 5xx, 408, codes 1, 2, 131000, 131016, 133004); honours Retry-After. */
    public static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";

    /** TRANSIENT: the {@link MetaCredentialsProvider} threw; nothing was sent. */
    public static final String CREDENTIALS_UNAVAILABLE = "CREDENTIALS_UNAVAILABLE";

    /**
     * PERMANENT: the access token is invalid, expired or lacks permission
     * (HTTP 401/403, codes 190, 10, 200). Provider health is DOWN until a later send succeeds.
     */
    public static final String CREDENTIAL_REJECTED = "CREDENTIAL_REJECTED";

    /** PERMANENT: the customer service window is closed; only a template can re-open it (code 131047). */
    public static final String REENGAGEMENT_REQUIRED = "REENGAGEMENT_REQUIRED";

    /** PERMANENT: the recipient cannot receive the message (code 131026). */
    public static final String RECIPIENT_UNDELIVERABLE = "RECIPIENT_UNDELIVERABLE";

    /** PERMANENT: template missing, paused, disabled or with mismatching parameters (codes 132000-132999). */
    public static final String TEMPLATE_REJECTED = "TEMPLATE_REJECTED";

    /**
     * PERMANENT: the business account is restricted, locked or has a payment
     * problem (codes 368, 131031, 131042). Provider health is DOWN until a later send succeeds.
     */
    public static final String ACCOUNT_RESTRICTED = "ACCOUNT_RESTRICTED";

    /** PERMANENT: any other refusal (other 4xx, unexpected status). */
    public static final String REJECTED = "REJECTED";

    /** PERMANENT, before sending: the request has no WhatsApp recipient. */
    public static final String INVALID_RECIPIENT = "INVALID_RECIPIENT";

    /**
     * PERMANENT, before sending: the content is not a template message, for
     * example because no template binding matched and the FreeMarker fallback
     * rendered text. Free-form text messages are not supported yet.
     */
    public static final String TEMPLATE_REQUIRED = "TEMPLATE_REQUIRED";

    private MetaErrorCodes() {
    }
}
