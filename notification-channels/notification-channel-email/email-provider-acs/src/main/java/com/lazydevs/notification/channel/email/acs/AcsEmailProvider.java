package com.lazydevs.notification.channel.email.acs;

import com.azure.communication.email.models.EmailAddress;
import com.azure.communication.email.models.EmailAttachment;
import com.azure.communication.email.models.EmailMessage;
import com.azure.core.credential.TokenCredential;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.BinaryData;
import com.lazydevs.notification.api.channel.EmailProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.FailureTypes;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.util.PiiMasking;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static com.lazydevs.notification.channel.email.acs.AcsEmailProperties.hasText;

/**
 * Azure Communication Services Email provider.
 *
 * <p>Configured per tenant: {@code ProviderRegistry} calls {@link #configure(Map)}
 * with the tenant's provider properties (see {@link AcsEmailProperties} for the
 * keys), then {@link #init()}.
 * Ways to obtain an instance:
 * <ul>
 *   <li>the prototype bean {@code acsEmailProvider} registered by
 *       {@link AcsEmailProviderAutoConfiguration} - each lookup is a fresh instance,
 *       and {@code credential=<bean name>} can pick a {@link TokenCredential} bean;</li>
 *   <li>the public no-arg constructor (fqcn or reflective instantiation) - connection
 *       string and {@code credential=default} work, bean-named credentials do not;</li>
 *   <li>{@link #withGateway(AcsEmailProperties, AcsEmailGateway)} - a ready instance over
 *       your own gateway, for tests.</li>
 * </ul>
 *
 * <p>The provider message id is the ACS operation id in both send modes.
 * The provider chooses it: {@link #operationIdFor(String, String, EmailMessage)}
 * derives it from the tenant, the request id and the message content, and the
 * gateway sends it as the {@code Operation-Id} header, so every retry of the same
 * message uses the same id.
 *
 * <p>Once ACS has accepted a message, a wait timeout or a failed status poll is
 * reported as a success with {@code acsStatus=UNCONFIRMED} in the provider
 * metadata, never as a retryable failure: resending could deliver it twice.
 */
@Slf4j
public class AcsEmailProvider implements EmailProvider {

    /** Built-in provider name ({@code EMAIL:acs}). */
    public static final String PROVIDER_NAME = AcsEmailProperties.PROVIDER_NAME;

    private static final String DEFAULT_ATTACHMENT_TYPE = "application/octet-stream";
    private static final String CODE_INVALID_MESSAGE = "ACS_INVALID_MESSAGE";
    private static final String CODE_SEND_FAILED = "ACS_SEND_FAILED";
    private static final String CODE_CANCELED = "ACS_CANCELED";
    private static final String METADATA_STATUS = "acsStatus";
    private static final String METADATA_REASON = "acsReason";
    /** Version tag of the operation id derivation; change it to change every id. */
    private static final String OPERATION_ID_SCHEME = "acs-operation-id/v1";

    private final AcsEmailClientFactory clientFactory;

    private AcsEmailProperties settings;
    private TokenCredential credential;
    private AcsEmailGateway gateway;

    /**
     * Reflective / fqcn construction: no Spring context, so {@code credential}
     * may only be {@code default} or blank with a connection string.
     */
    public AcsEmailProvider() {
        this(null, AcsEmailProvider.class.getClassLoader());
    }

    /**
     * Construction by {@link AcsEmailProviderAutoConfiguration}.
     *
     * @param namedCredentials {@link TokenCredential} beans by bean name ({@code null} outside Spring)
     * @param classLoader      the application class loader, used to detect azure-identity
     */
    public AcsEmailProvider(Map<String, TokenCredential> namedCredentials, ClassLoader classLoader) {
        this.clientFactory = new AcsEmailClientFactory(namedCredentials, classLoader);
    }

    /**
     * Test seam: an already configured and initialised provider.
     */
    AcsEmailProvider(AcsEmailProperties settings, AcsEmailGateway gateway) {
        this.clientFactory = null;
        this.settings = settings;
        this.gateway = gateway;
    }

    /**
     * A ready-to-use provider over your own {@link AcsEmailGateway}, for testing an
     * integration without Azure (mock the gateway, or wrap a test double).
     *
     * <p>The instance is already configured and initialised: {@link #configure(Map)}
     * and {@link #init()} are no-ops on it, so a {@code ProviderRegistry} can still
     * call them.
     *
     * @param settings the settings, for example from {@link AcsEmailProperties#fromMap(Map)}
     * @param gateway  the gateway that performs the send
     * @return the provider
     * @since 1.1.1
     */
    public static AcsEmailProvider withGateway(AcsEmailProperties settings, AcsEmailGateway gateway) {
        return new AcsEmailProvider(Objects.requireNonNull(settings, "settings"),
                Objects.requireNonNull(gateway, "gateway"));
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    /**
     * Parse and validate the tenant's settings and resolve the credential.
     * Fails fast with a {@code ProviderConfigurationException}.
     */
    @Override
    public void configure(Map<String, Object> properties) {
        if (clientFactory == null) {
            // Built by withGateway(...): settings and gateway were supplied up front.
            log.debug("ACS email provider was built with a gateway; ignoring configure(...)");
            return;
        }
        AcsEmailProperties parsed = AcsEmailProperties.fromMap(properties);
        this.credential = clientFactory.resolveCredential(parsed);
        this.settings = parsed;
        // A re-configured instance must not keep a client built from the old settings.
        this.gateway = null;
        log.debug("ACS email provider configured: {}", parsed);
    }

    /**
     * Build the SDK client and the gateway. No network call happens here.
     */
    @Override
    public void init() {
        if (settings == null) {
            throw new IllegalStateException("AcsEmailProvider.configure(...) must be called before init()");
        }
        if (gateway != null) {
            return;
        }
        this.gateway = new SdkAcsEmailGateway(
                clientFactory.createClient(settings, credential),
                settings.sendMode(),
                settings.waitTimeout());
        log.info("ACS email provider initialized: endpoint={}, sendMode={}, sdkRetries={}",
                settings.hasConnectionString() ? "(from connection string)" : settings.endpoint(),
                settings.sendMode(), settings.sdkRetries());
    }

    @Override
    public boolean isHealthy() {
        // ACS Email has no cheap read-only probe; report whether init() completed.
        return gateway != null;
    }

    @Override
    public SendResult send(NotificationRequest request, RenderedContent content) {
        if (gateway == null) {
            throw new IllegalStateException("AcsEmailProvider is not initialized; call configure(...) and init()");
        }
        EmailRecipient recipient = (EmailRecipient) request.getRecipient();

        EmailMessage message;
        try {
            message = toEmailMessage(request, content);
        } catch (InvalidMessageException e) {
            return SendResult.failure(CODE_INVALID_MESSAGE, PiiMasking.redact(e.getMessage()), FailureType.PERMANENT);
        }

        UUID operationId = hasText(request.getRequestId())
                ? operationIdFor(request.getTenantId(), request.getRequestId(), message)
                // Without a request id there is no "same logical send" to protect.
                : UUID.randomUUID();
        try {
            AcsSendOutcome outcome = gateway.send(message, operationId);
            return toSendResult(outcome, recipient);
        } catch (Exception e) {
            log.error("Failed to send email via ACS: to={}, operationId={}, error={}",
                    PiiMasking.maskEmail(recipient.to()), operationId, PiiMasking.redact(e.getMessage()));
            // The id travels with the failure so the attempt can be reconciled; a retry
            // derives the same id again. A transient HTTP error may say how long to
            // wait; the retry executor honours that hint.
            FailureType type = classifyAcs(e);
            Map<String, Object> metadata = type == FailureType.TRANSIENT
                    ? retryAfter(e, Instant.now())
                            .<Map<String, Object>>map(d -> Map.of(SendResult.RETRY_AFTER_METADATA_KEY, d.toString()))
                            .orElse(null)
                    : null;
            return new SendResult(false, operationId.toString(), e.getClass().getSimpleName(),
                    PiiMasking.redact(describe(e)), type, Instant.now(), metadata);
        }
    }

    private static SendResult toSendResult(AcsSendOutcome outcome, EmailRecipient recipient) {
        String operationId = outcome.operationId();
        return switch (outcome.status()) {
            case SUCCEEDED, SUBMITTED -> {
                log.debug("Email sent via ACS: to={}, operationId={}, status={}",
                        PiiMasking.maskEmail(recipient.to()), operationId, outcome.status());
                yield SendResult.success(operationId, Map.of(METADATA_STATUS, outcome.status().name()));
            }
            // A final FAILED status is ACS's own verdict on this message; resending
            // the same payload will not change it.
            case FAILED -> failure(operationId, outcome, CODE_SEND_FAILED,
                    "ACS reported the send as failed", FailureType.PERMANENT);
            case CANCELED -> failure(operationId, outcome, CODE_CANCELED,
                    "ACS operation was canceled", FailureType.UNKNOWN);
            // Accepted but not confirmed: the message may still go out, so a retry
            // could deliver it twice. Report success and keep the id for reconciliation.
            case UNCONFIRMED, TIMED_OUT -> unconfirmed(operationId, outcome, recipient);
        };
    }

    private static SendResult unconfirmed(String operationId, AcsSendOutcome outcome, EmailRecipient recipient) {
        log.warn("ACS accepted email operation {} (to={}) but did not confirm a final status: {} {};"
                        + " not retrying, reconcile with the operation id",
                operationId, PiiMasking.maskEmail(recipient.to()), outcome.errorCode(),
                PiiMasking.redact(outcome.errorMessage()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(METADATA_STATUS, AcsSendOutcome.Status.UNCONFIRMED.name());
        if (hasText(outcome.errorCode())) {
            metadata.put(METADATA_REASON, outcome.errorCode());
        }
        return SendResult.success(operationId, metadata);
    }

    private static SendResult failure(String operationId, AcsSendOutcome outcome, String defaultCode,
                                      String defaultMessage, FailureType type) {
        log.warn("ACS email operation {} ended with {}: {} {}", operationId, outcome.status(),
                outcome.errorCode(), PiiMasking.redact(outcome.errorMessage()));
        return new SendResult(false, operationId,
                hasText(outcome.errorCode()) ? outcome.errorCode() : defaultCode,
                hasText(outcome.errorMessage()) ? PiiMasking.redact(outcome.errorMessage()) : defaultMessage,
                type, Instant.now(), Map.of(METADATA_STATUS, outcome.status().name()));
    }

    /**
     * Deterministic ACS operation id for one message of one request.
     *
     * <p>Name-based UUID ({@link UUID#nameUUIDFromBytes}, version 3) over the
     * scheme tag, the tenant id, the request id and a SHA-256 fingerprint of the
     * message: to, cc and bcc addresses, subject, plain-text body, HTML body and
     * attachment names.
     * MD5 inside version 3 only spreads the bytes into a UUID; it is not a
     * security boundary.
     * The fingerprint keeps the id distinct for batch items, which share one
     * request id, and for two different messages sent under one request id.
     * Every field is length-prefixed, so values cannot run into each other.
     *
     * @param tenantId  tenant id, may be {@code null}
     * @param requestId request id, may be {@code null}
     * @param message   the SDK message
     * @return the same UUID for the same inputs, on every JVM
     */
    static UUID operationIdFor(String tenantId, String requestId, EmailMessage message) {
        Canonical name = new Canonical();
        name.text(OPERATION_ID_SCHEME);
        name.text(tenantId);
        name.text(requestId);
        name.bytes(fingerprint(message));
        return UUID.nameUUIDFromBytes(name.toByteArray());
    }

    private static byte[] fingerprint(EmailMessage message) {
        Canonical content = new Canonical();
        content.list(addressValues(message.getToRecipients()));
        content.list(addressValues(message.getCcRecipients()));
        content.list(addressValues(message.getBccRecipients()));
        content.text(message.getSubject());
        content.text(message.getBodyPlainText());
        content.text(message.getBodyHtml());
        content.list(message.getAttachments() == null ? null
                : message.getAttachments().stream().map(EmailAttachment::getName).toList());
        try {
            return MessageDigest.getInstance("SHA-256").digest(content.toByteArray());
        } catch (NoSuchAlgorithmException e) {
            // Every JDK must provide SHA-256.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static List<String> addressValues(List<EmailAddress> addresses) {
        return addresses == null ? null : addresses.stream().map(EmailAddress::getAddress).toList();
    }

    /** Length-prefixed, null-aware byte encoding of the operation id inputs. */
    private static final class Canonical {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final DataOutputStream out = new DataOutputStream(buffer);

        void text(String value) {
            bytes(value == null ? null : value.getBytes(StandardCharsets.UTF_8));
        }

        void list(List<String> values) {
            try {
                out.writeInt(values == null ? -1 : values.size());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (values != null) {
                values.forEach(this::text);
            }
        }

        void bytes(byte[] value) {
            try {
                out.writeInt(value == null ? -1 : value.length);
                if (value != null) {
                    out.write(value);
                }
            } catch (IOException e) {
                // ByteArrayOutputStream does not throw.
                throw new UncheckedIOException(e);
            }
        }

        byte[] toByteArray() {
            return buffer.toByteArray();
        }
    }

    /**
     * Map the library's request onto the SDK message.
     */
    EmailMessage toEmailMessage(NotificationRequest request, RenderedContent content) {
        EmailRecipient recipient = (EmailRecipient) request.getRecipient();
        if (content == null || (!content.hasHtml() && !content.hasText())) {
            throw new InvalidMessageException("email has neither an HTML nor a plain-text body");
        }

        String subject = content.subject();
        if (!hasText(subject)) {
            subject = recipient.subject();
        }

        EmailMessage message = new EmailMessage()
                .setSenderAddress(settings.sender())
                .setSubject(subject == null ? "" : subject)
                .setToRecipients(addresses(List.of(recipient.to())));

        List<EmailAddress> cc = addresses(recipient.cc());
        if (!cc.isEmpty()) {
            message.setCcRecipients(cc);
        }
        List<EmailAddress> bcc = addresses(recipient.bcc());
        if (!bcc.isEmpty()) {
            message.setBccRecipients(bcc);
        }
        if (content.hasHtml()) {
            message.setBodyHtml(content.htmlBody());
        }
        if (content.hasText()) {
            message.setBodyPlainText(content.textBody());
        }

        List<EmailAddress> replyTo = hasText(recipient.replyTo())
                ? addresses(List.of(recipient.replyTo()))
                : addresses(settings.replyTo());
        if (!replyTo.isEmpty()) {
            message.setReplyTo(replyTo);
        }

        List<EmailAttachment> attachments = attachments(request.getAttachments());
        if (!attachments.isEmpty()) {
            message.setAttachments(attachments);
        }

        if (settings.userEngagementTrackingDisabled() != null) {
            message.setUserEngagementTrackingDisabled(settings.userEngagementTrackingDisabled());
        }
        return message;
    }

    private static List<EmailAddress> addresses(List<String> values) {
        List<EmailAddress> out = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (hasText(value)) {
                    out.add(new EmailAddress(value.trim()));
                }
            }
        }
        return out;
    }

    private static List<EmailAttachment> attachments(List<NotificationRequest.Attachment> attachments) {
        List<EmailAttachment> out = new ArrayList<>();
        if (attachments == null) {
            return out;
        }
        for (NotificationRequest.Attachment attachment : attachments) {
            if (attachment == null) {
                continue;
            }
            if (attachment.content() == null) {
                // ACS needs the bytes inline; silently dropping the attachment would be worse.
                throw new InvalidMessageException("attachment '" + attachment.filename()
                        + "' has no inline content; ACS does not fetch attachments by URL");
            }
            if (!hasText(attachment.filename())) {
                throw new InvalidMessageException("every attachment needs a filename");
            }
            out.add(new EmailAttachment(
                    attachment.filename(),
                    hasText(attachment.contentType()) ? attachment.contentType() : DEFAULT_ATTACHMENT_TYPE,
                    BinaryData.fromBytes(attachment.content())));
        }
        return out;
    }

    /**
     * Map an ACS SDK exception to a {@link FailureType} for retry decisions (DD-13).
     *
     * <ul>
     *   <li>{@link HttpResponseException} - the HTTP status decides via
     *       {@link FailureTypes#fromHttpStatus}: 408, 429 (throttling) and 5xx are
     *       {@link FailureType#TRANSIENT}; other 4xx, including 401/403 credential
     *       problems, are {@link FailureType#PERMANENT}.</li>
     *   <li>{@link TimeoutException} or any {@code IOException} (including
     *       {@code HttpTimeoutException}) in the cause chain -
     *       {@link FailureType#TRANSIENT}.</li>
     *   <li>Anything else - {@link FailureType#UNKNOWN}, defer to the retry predicate.</li>
     * </ul>
     *
     * <p>ACS sends {@code Retry-After} with 429; {@link #retryAfter} reads it
     * so the retry executor can wait at least that long.
     */
    static FailureType classifyAcs(Throwable t) {
        if (t instanceof HttpResponseException hre && hre.getResponse() != null) {
            return FailureTypes.fromHttpStatus(hre.getResponse().getStatusCode());
        }
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof TimeoutException) {
                return FailureType.TRANSIENT;
            }
        }
        if (FailureTypes.fromException(t) == FailureType.TRANSIENT) {
            return FailureType.TRANSIENT;
        }
        return FailureType.UNKNOWN;
    }

    /**
     * The {@code Retry-After} delay of an ACS HTTP error, measured from
     * {@code now}. The header is either a number of seconds or an HTTP-date
     * (RFC 9110, section 10.2.3).
     *
     * @return the delay; empty when {@code t} is not an HTTP error, the
     *         header is absent or malformed, or the delay is not positive
     */
    static Optional<Duration> retryAfter(Throwable t, Instant now) {
        if (!(t instanceof HttpResponseException hre) || hre.getResponse() == null) {
            return Optional.empty();
        }
        HttpHeaders headers = hre.getResponse().getHeaders();
        String value = headers == null ? null : headers.getValue(HttpHeaderName.RETRY_AFTER);
        if (!hasText(value)) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        Duration delay;
        try {
            delay = trimmed.chars().allMatch(c -> c >= '0' && c <= '9')
                    ? Duration.ofSeconds(Long.parseLong(trimmed))
                    : Duration.between(now, ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant());
        } catch (NumberFormatException | DateTimeException e) {
            return Optional.empty();
        }
        return delay.isNegative() || delay.isZero() ? Optional.empty() : Optional.of(delay);
    }

    /**
     * Error message for the {@code SendResult}, with a hint for credential failures.
     */
    static String describe(Throwable t) {
        String message = t.getMessage();
        if (t instanceof HttpResponseException hre) {
            HttpResponse response = hre.getResponse();
            int status = response == null ? 0 : response.getStatusCode();
            if (status == 401 || status == 403) {
                return message + " (HTTP " + status + ": check the ACS credentials - the connection string"
                        + " access key, or the role assignment of the TokenCredential identity on the"
                        + " Communication Services resource)";
            }
        }
        return message;
    }

    /** The request cannot be expressed as an ACS message; retrying will not help. */
    private static final class InvalidMessageException extends RuntimeException {
        InvalidMessageException(String message) {
            super(message);
        }
    }

    // Package-private accessors for tests.

    AcsEmailProperties settings() {
        return settings;
    }

    TokenCredential credential() {
        return credential;
    }

    AcsEmailGateway gateway() {
        return gateway;
    }
}
