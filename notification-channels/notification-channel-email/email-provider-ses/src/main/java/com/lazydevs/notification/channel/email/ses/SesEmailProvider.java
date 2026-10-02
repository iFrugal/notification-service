package com.lazydevs.notification.channel.email.ses;

import com.lazydevs.notification.api.channel.EmailProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.FailureTypes;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.util.PiiMasking;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AWS SES email provider implementation.
 *
 * <p>{@link #withClient(SesV2Client)} builds an instance over your own client, for
 * testing an integration without AWS.
 */
@Slf4j
public class SesEmailProvider implements EmailProvider {

    private static final String CHARSET_UTF8 = "UTF-8";

    private String region;
    private String fromAddress;
    private String fromName;
    private String configurationSetName;

    private SesV2Client sesClient;
    /** Set when the client came from {@link #withClient(SesV2Client)}; the caller owns it. */
    private boolean clientInjected;

    /**
     * Reflective / bean construction; {@link #init()} builds the SES client.
     */
    public SesEmailProvider() {
        // Settings arrive through configure(...).
    }

    /**
     * A provider that sends through {@code client}, for example a Mockito mock of
     * {@link SesV2Client}.
     * Call {@link #configure(Map)} for the sender settings as usual; {@link #init()}
     * keeps the given client instead of building one, and {@link #destroy()} does not
     * close it.
     *
     * @param client the SES v2 client
     * @return the provider
     * @since 1.1.1
     */
    public static SesEmailProvider withClient(SesV2Client client) {
        SesEmailProvider provider = new SesEmailProvider();
        provider.sesClient = Objects.requireNonNull(client, "client");
        provider.clientInjected = true;
        return provider;
    }

    @Override
    public String getProviderName() {
        return "ses";
    }

    @Override
    public void configure(Map<String, Object> properties) {
        this.region = getString(properties, "region", "us-east-1");
        this.fromAddress = getString(properties, "from-address", getString(properties, "fromAddress", null));
        this.fromName = getString(properties, "from-name", getString(properties, "fromName", null));
        this.configurationSetName = getString(properties, "configuration-set", null);

        log.debug("SES provider configured: region={}, from={}", region, PiiMasking.maskEmail(fromAddress));
    }

    @Override
    public void init() {
        if (clientInjected) {
            log.info("AWS SES email provider initialized with a supplied client");
            return;
        }
        sesClient = SesV2Client.builder()
                .region(Region.of(region))
                .build();

        log.info("AWS SES email provider initialized: region={}", region);
    }

    @Override
    public void destroy() {
        if (sesClient != null && !clientInjected) {
            sesClient.close();
            log.debug("SES client closed");
        }
    }

    @Override
    public SendResult send(NotificationRequest request, RenderedContent content) {
        EmailRecipient recipient = (EmailRecipient) request.getRecipient();

        try {
            // Build destination
            Destination.Builder destinationBuilder = Destination.builder()
                    .toAddresses(recipient.to());

            if (recipient.cc() != null && !recipient.cc().isEmpty()) {
                destinationBuilder.ccAddresses(recipient.cc());
            }
            if (recipient.bcc() != null && !recipient.bcc().isEmpty()) {
                destinationBuilder.bccAddresses(recipient.bcc());
            }

            // Build email content
            EmailContent.Builder emailContentBuilder = EmailContent.builder();

            // Subject
            String subject = content.subject();
            if (subject == null || subject.isBlank()) {
                subject = recipient.subject();
            }

            // Body
            Body.Builder bodyBuilder = Body.builder();
            if (content.hasHtml()) {
                bodyBuilder.html(Content.builder().data(content.htmlBody()).charset(CHARSET_UTF8).build());
            }
            if (content.hasText()) {
                bodyBuilder.text(Content.builder().data(content.textBody()).charset(CHARSET_UTF8).build());
            }

            Message message = Message.builder()
                    .subject(Content.builder().data(subject).charset(CHARSET_UTF8).build())
                    .body(bodyBuilder.build())
                    .build();

            emailContentBuilder.simple(message);

            // Build request
            SendEmailRequest.Builder sendRequestBuilder = SendEmailRequest.builder()
                    .fromEmailAddress(buildFromAddress())
                    .destination(destinationBuilder.build())
                    .content(emailContentBuilder.build());

            // Reply-to
            if (recipient.replyTo() != null && !recipient.replyTo().isBlank()) {
                sendRequestBuilder.replyToAddresses(recipient.replyTo());
            }

            // Configuration set
            if (configurationSetName != null && !configurationSetName.isBlank()) {
                sendRequestBuilder.configurationSetName(configurationSetName);
            }

            // Send
            SendEmailResponse response = sesClient.sendEmail(sendRequestBuilder.build());

            log.debug("Email sent via SES: to={}, messageId={}",
                    PiiMasking.maskEmail(recipient.to()), response.messageId());

            return SendResult.success(response.messageId());

        } catch (Exception e) {
            String error = PiiMasking.redact(e.getMessage());
            log.error("Failed to send email via SES: to={}, error={}",
                    PiiMasking.maskEmail(recipient.to()), error);
            return SendResult.failure(
                    e.getClass().getSimpleName(), error, classifySes(e));
        }
    }

    /**
     * Map an AWS SES SDK exception to a {@link FailureType} for retry
     * decisions (DD-13).
     *
     * <p>AWS SDK v2 throws three flavours we care about:
     * <ul>
     *   <li>{@link SdkClientException} - client-side failure. A read
     *       timeout or an API-call (attempt) timeout is
     *       {@link FailureType#AMBIGUOUS}: SES may have accepted the
     *       message before the response was lost, so a resend could
     *       deliver it twice. Anything else, including connect failures
     *       and connect timeouts, is {@link FailureType#TRANSIENT}: the
     *       request never reached SES.</li>
     *   <li>{@link AwsServiceException} — server returned an error
     *       response. The HTTP status drives the classification via
     *       {@link FailureTypes#fromHttpStatus}, with a few SES-specific
     *       overrides for known permanent conditions.</li>
     *   <li>Anything else — {@link FailureType#UNKNOWN}, defer to the
     *       default predicate.</li>
     * </ul>
     *
     * <p>SES v2-specific PERMANENT overrides:
     * <ul>
     *   <li>{@code AccountSuspendedException} — terminal account state;
     *       no retry will succeed until ops re-enables the account.</li>
     *   <li>{@code SendingPausedException} — operator paused sending
     *       (account-wide or configuration-set-scoped). Same logic.</li>
     *   <li>{@code MailFromDomainNotVerifiedException} — config issue.</li>
     *   <li>{@code MessageRejectedException} — content / recipient
     *       problem; SES rejected the payload.</li>
     *   <li>{@code BadRequestException} — malformed call.</li>
     * </ul>
     *
     * <p>{@code TooManyRequestsException} and
     * {@code LimitExceededException} are throttling — fall through to
     * the HTTP-status branch which already maps 429 / 503 to TRANSIENT.
     */
    static FailureType classifySes(Throwable t) {
        if (t instanceof SdkClientException) {
            return timedOutAfterSubmit(t) ? FailureType.AMBIGUOUS : FailureType.TRANSIENT;
        }
        // SES v2-specific permanent conditions. These inherit
        // AwsServiceException but always represent config / account-state
        // issues rather than transient hiccups.
        if (t instanceof AccountSuspendedException
                || t instanceof SendingPausedException
                || t instanceof MailFromDomainNotVerifiedException
                || t instanceof MessageRejectedException
                || t instanceof BadRequestException) {
            return FailureType.PERMANENT;
        }
        if (t instanceof AwsServiceException ase) {
            // Throttling / SlowDown gets 429 / 503 from AWS; the
            // status-code path catches them as TRANSIENT. We don't
            // need special-cases for TooManyRequests / LimitExceeded.
            return FailureTypes.fromHttpStatus(ase.statusCode());
        }
        // I/O signal in cause chain → transient.
        FailureType ioGuess = FailureTypes.fromException(t);
        if (ioGuess == FailureType.TRANSIENT) {
            return FailureType.TRANSIENT;
        }
        return FailureType.UNKNOWN;
    }

    /**
     * Whether a client-side failure is a timeout that may have struck after
     * the request reached SES: an API-call or API-call-attempt timeout, or a
     * socket read timeout. A connect failure anywhere in the cause chain,
     * including a connect timeout, means nothing was sent
     * ({@link FailureTypes#fromExceptionAfterSubmit} says TRANSIENT).
     */
    private static boolean timedOutAfterSubmit(Throwable t) {
        if (FailureTypes.fromExceptionAfterSubmit(t) == FailureType.TRANSIENT) {
            return false;
        }
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof ApiCallTimeoutException
                    || cur instanceof ApiCallAttemptTimeoutException
                    || cur instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isHealthy() {
        try {
            // Simple health check — fetch account details. SesV2Client.getAccount
            // requires a GetAccountRequest (no no-arg overload exists in AWS SDK v2),
            // so we pass the builder-consumer form.
            sesClient.getAccount(b -> {});
            return true;
        } catch (Exception e) {
            log.warn("SES health check failed: {}", PiiMasking.redact(e.getMessage()));
            return false;
        }
    }

    private String buildFromAddress() {
        if (fromName != null && !fromName.isBlank()) {
            return String.format("%s <%s>", fromName, fromAddress);
        }
        return fromAddress;
    }

    private String getString(Map<String, Object> props, String key, String defaultValue) {
        Object value = props.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }
}
