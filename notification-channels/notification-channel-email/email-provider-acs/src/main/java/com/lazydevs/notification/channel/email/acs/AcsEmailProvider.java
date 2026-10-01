package com.lazydevs.notification.channel.email.acs;

import com.azure.communication.email.models.EmailAddress;
import com.azure.communication.email.models.EmailAttachment;
import com.azure.communication.email.models.EmailMessage;
import com.azure.core.credential.TokenCredential;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.BinaryData;
import com.lazydevs.notification.api.channel.EmailProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.FailureTypes;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static com.lazydevs.notification.channel.email.acs.AcsEmailProperties.hasText;

/**
 * Azure Communication Services Email provider.
 *
 * <p>Configured per tenant: {@code ProviderRegistry} calls {@link #configure(Map)}
 * with the tenant's provider properties (see {@link AcsEmailProperties} for the
 * keys), then {@link #init()}.
 * Two ways to obtain an instance:
 * <ul>
 *   <li>the prototype bean {@code acsEmailProvider} registered by
 *       {@link AcsEmailProviderAutoConfiguration} - each lookup is a fresh instance,
 *       and {@code credential=<bean name>} can pick a {@link TokenCredential} bean;</li>
 *   <li>the public no-arg constructor (fqcn or reflective instantiation) - connection
 *       string and {@code credential=default} work, bean-named credentials do not.</li>
 * </ul>
 *
 * <p>The provider message id is the ACS operation id in both send modes.
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
            return SendResult.failure(CODE_INVALID_MESSAGE, e.getMessage(), FailureType.PERMANENT);
        }

        try {
            AcsSendOutcome outcome = gateway.send(message);
            return toSendResult(outcome, recipient);
        } catch (Exception e) {
            log.error("Failed to send email via ACS: to={}, error={}", recipient.to(), e.getMessage());
            return SendResult.failure(e.getClass().getSimpleName(), describe(e), classifyAcs(e));
        }
    }

    private static SendResult toSendResult(AcsSendOutcome outcome, EmailRecipient recipient) {
        String operationId = outcome.operationId();
        return switch (outcome.status()) {
            case SUCCEEDED, SUBMITTED -> {
                log.debug("Email sent via ACS: to={}, operationId={}, status={}",
                        recipient.to(), operationId, outcome.status());
                yield SendResult.success(operationId, Map.of(METADATA_STATUS, outcome.status().name()));
            }
            // A final FAILED status is ACS's own verdict on this message; resending
            // the same payload will not change it.
            case FAILED -> failure(operationId, outcome, CODE_SEND_FAILED,
                    "ACS reported the send as failed", FailureType.PERMANENT);
            case CANCELED -> failure(operationId, outcome, CODE_CANCELED,
                    "ACS operation was canceled", FailureType.UNKNOWN);
            // The message may still go out; the operation id is kept for reconciliation.
            case TIMED_OUT -> failure(operationId, outcome, "ACS_WAIT_TIMEOUT",
                    "ACS did not report a final status in time", FailureType.TRANSIENT);
        };
    }

    private static SendResult failure(String operationId, AcsSendOutcome outcome, String defaultCode,
                                      String defaultMessage, FailureType type) {
        log.warn("ACS email operation {} ended with {}: {} {}", operationId, outcome.status(),
                outcome.errorCode(), outcome.errorMessage());
        return new SendResult(false, operationId,
                hasText(outcome.errorCode()) ? outcome.errorCode() : defaultCode,
                hasText(outcome.errorMessage()) ? outcome.errorMessage() : defaultMessage,
                type, Instant.now(), Map.of(METADATA_STATUS, outcome.status().name()));
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
     * <p>ACS sends {@code Retry-After} with 429; {@code SendResult} has no field
     * for a delay hint, so the retry executor's own backoff applies.
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
