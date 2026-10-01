package com.lazydevs.notification.channel.sms.twilio;

import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.channel.SmsProvider;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.FailureTypes;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.model.SmsRecipient;
import com.lazydevs.notification.api.util.PiiMasking;
import com.twilio.Twilio;
import com.twilio.exception.ApiException;
import com.twilio.exception.AuthenticationException;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.rest.api.v2010.account.MessageCreator;
import com.twilio.type.PhoneNumber;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;

/**
 * Twilio SMS provider implementation.
 *
 * <p>The default instance initialises the process-wide Twilio SDK with
 * {@code Twilio.init}, so every instance in the JVM shares the last account
 * configured.
 * {@link #withClient(TwilioRestClient)} builds an instance that sends through its own
 * client instead, without touching the global SDK state; use it for tests (a mocked
 * client) and when one JVM needs more than one Twilio account.
 */
@Slf4j
public class TwilioSmsProvider implements SmsProvider {

    private String accountSid;
    private String authToken;
    private String fromNumber;
    private boolean initialized = false;
    /** Set by {@link #withClient(TwilioRestClient)}; {@code null} means the global SDK client. */
    private TwilioRestClient client;

    /**
     * Reflective / bean construction; {@link #init()} calls {@code Twilio.init}.
     */
    public TwilioSmsProvider() {
        // Settings arrive through configure(...).
    }

    /**
     * A provider that sends through {@code client}, for example a Mockito mock of
     * {@link TwilioRestClient} or one built with its own {@code HttpClient}.
     * Call {@link #configure(Map)} for the {@code from} number as usual; the
     * account settings are not needed, and {@link #init()} does not call
     * {@code Twilio.init}.
     * The instance can send right away.
     *
     * @param client the Twilio REST client
     * @return the provider
     * @since 1.1.1
     */
    public static TwilioSmsProvider withClient(TwilioRestClient client) {
        TwilioSmsProvider provider = new TwilioSmsProvider();
        provider.client = Objects.requireNonNull(client, "client");
        provider.initialized = true;
        return provider;
    }

    @Override
    public String getProviderName() {
        return "twilio";
    }

    @Override
    public void configure(Map<String, Object> properties) {
        this.accountSid = getString(properties, "account-sid", getString(properties, "accountSid", null));
        this.authToken = getString(properties, "auth-token", getString(properties, "authToken", null));
        this.fromNumber = getString(properties, "from", null);

        log.debug("Twilio SMS provider configured: from={}", PiiMasking.maskPhone(fromNumber));
    }

    @Override
    public void init() {
        if (client != null) {
            log.info("Twilio SMS provider initialized with a supplied client");
            return;
        }
        if (accountSid == null || authToken == null) {
            throw new IllegalStateException("Twilio account-sid and auth-token are required");
        }

        Twilio.init(accountSid, authToken);
        initialized = true;

        log.info("Twilio SMS provider initialized");
    }

    @Override
    public void destroy() {
        // Twilio SDK doesn't require explicit cleanup
        log.debug("Twilio SMS provider destroyed");
    }

    @Override
    public SendResult send(NotificationRequest request, RenderedContent content) {
        if (!initialized) {
            // Configuration error — retrying won't help.
            return SendResult.failure("NOT_INITIALIZED",
                    "Twilio provider not initialized", FailureType.PERMANENT);
        }

        SmsRecipient recipient = (SmsRecipient) request.getRecipient();

        try {
            MessageCreator creator = Message.creator(
                    new PhoneNumber(recipient.phoneNumber()),
                    new PhoneNumber(fromNumber),
                    content.textBody()
            );
            Message message = client != null ? creator.create(client) : creator.create();

            log.debug("SMS sent via Twilio: to={}, sid={}",
                    PiiMasking.maskPhone(recipient.phoneNumber()), message.getSid());

            return SendResult.success(message.getSid(), Map.of(
                    "status", message.getStatus().toString(),
                    "price", message.getPrice() != null ? message.getPrice() : "N/A"
            ));

        } catch (Exception e) {
            String error = PiiMasking.redact(e.getMessage());
            log.error("Failed to send SMS via Twilio: to={}, error={}",
                    PiiMasking.maskPhone(recipient.phoneNumber()), error);
            return SendResult.failure(
                    e.getClass().getSimpleName(), error, classifyTwilio(e));
        }
    }

    /**
     * Map a Twilio SDK exception to a {@link FailureType} for retry
     * decisions (DD-13).
     *
     * <p>Twilio errors fall into three buckets:
     * <ul>
     *   <li>{@link AuthenticationException} — wrong account-sid /
     *       auth-token. Always {@link FailureType#PERMANENT} — no
     *       retry will succeed until config is fixed.</li>
     *   <li>{@link ApiException} — server returned a typed error.
     *       Twilio's {@code getStatusCode()} carries the HTTP status;
     *       map via {@link FailureTypes#fromHttpStatus}. Common
     *       PERMANENT cases (e.g. error code 21211 "Invalid To
     *       Number") show up as 4xx and classify correctly.</li>
     *   <li>I/O / network — caught by
     *       {@link FailureTypes#fromException} via cause chain.</li>
     * </ul>
     */
    static FailureType classifyTwilio(Throwable t) {
        if (t instanceof AuthenticationException) {
            return FailureType.PERMANENT;
        }
        if (t instanceof ApiException ae) {
            // Twilio's getStatusCode returns an Integer; null means the
            // SDK didn't get a status (network failure pre-response).
            // Treat null as transient — same as a connection error.
            Integer status = ae.getStatusCode();
            if (status == null) {
                return FailureType.TRANSIENT;
            }
            return FailureTypes.fromHttpStatus(status);
        }
        FailureType ioGuess = FailureTypes.fromException(t);
        if (ioGuess == FailureType.TRANSIENT) {
            return FailureType.TRANSIENT;
        }
        return FailureType.UNKNOWN;
    }

    @Override
    public boolean isHealthy() {
        return initialized;
    }

    private String getString(Map<String, Object> props, String key, String defaultValue) {
        Object value = props.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }
}
