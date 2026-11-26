package com.lazydevs.notification.channel.whatsapp.meta;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.channel.WhatsAppProvider;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.model.WhatsAppRecipient;
import com.lazydevs.notification.api.util.PiiMasking;
import com.lazydevs.notification.channel.whatsapp.WhatsAppTemplateMessage;
import com.lazydevs.notification.channel.whatsapp.meta.MetaErrorClassifier.MetaFailure;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * WhatsApp provider over Meta's WhatsApp Cloud API (DD-35): sends the
 * {@link WhatsAppTemplateMessage} that the {@code WHATSAPP} content renderer put
 * into {@link RenderedContent#payload()} as a template message,
 * {@code POST <base-url>/<api-version>/<phone-number-id>/messages}.
 *
 * <p>Configured per tenant: {@code ProviderRegistry} calls {@link #configure(Map)}
 * with the tenant's provider properties (see {@link MetaSettings}), then
 * {@link #init()}. Neither calls the network.
 * Ways to obtain an instance:
 * <ul>
 *   <li>the prototype bean {@code metaWhatsappProvider} registered by
 *       {@link MetaWhatsAppProviderAutoConfiguration}, which uses a
 *       {@link MetaHttpTransport} bean and a {@link MetaCredentialsProvider} bean when
 *       there is exactly one;</li>
 *   <li>the public no-arg constructor (fqcn or reflective instantiation): the JDK
 *       transport and the credentials from the properties.</li>
 * </ul>
 *
 * <p>No retry loop: every failure is classified (see {@link MetaErrorCodes}) and
 * returned with a Retry-After hint where Meta gives one, and the library's retry
 * executor decides. The request id goes out as {@code biz_opaque_callback_data},
 * so a status webhook can be correlated before the message id is stored.
 * A rejected credential or a restricted account turns the provider's health DOWN
 * until a later send succeeds.
 *
 * <p>Logs never contain the access token, the recipient's number or a template value.
 */
@Slf4j
public class MetaWhatsAppProvider implements WhatsAppProvider {

    /** Built-in provider name ({@code WHATSAPP:meta}). */
    public static final String PROVIDER_NAME = MetaSettings.PROVIDER_NAME;

    private static final Pattern RECIPIENT = Pattern.compile("\\+?\\d{6,15}");
    private static final String STATUS = "status";

    private final MetaHttpTransport transport;
    private final MetaCredentialsProvider credentialsProvider;
    private final Clock clock;

    private volatile MetaSettings settings;
    private volatile MetaCredentialsProvider credentials;
    private volatile boolean initialized;
    private volatile String downReason;

    /**
     * Reflective / fqcn construction: the JDK transport, and the credentials from
     * the provider properties.
     */
    public MetaWhatsAppProvider() {
        this(null, null);
    }

    /**
     * Construction with collaborators, as the auto-configuration does.
     *
     * @param transport           the HTTP transport; {@code null} means {@link JdkMetaHttpTransport#shared()}
     * @param credentialsProvider resolves credentials per tenant; {@code null} means the provider properties
     */
    public MetaWhatsAppProvider(MetaHttpTransport transport, MetaCredentialsProvider credentialsProvider) {
        this(transport, credentialsProvider, Clock.systemUTC());
    }

    MetaWhatsAppProvider(MetaHttpTransport transport, MetaCredentialsProvider credentialsProvider, Clock clock) {
        this.transport = transport != null ? transport : JdkMetaHttpTransport.shared();
        this.credentialsProvider = credentialsProvider;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    /**
     * Parse and validate the tenant's settings. Without a
     * {@link MetaCredentialsProvider}, {@code access-token} and
     * {@code phone-number-id} are required.
     */
    @Override
    public void configure(Map<String, Object> properties) {
        MetaSettings parsed = MetaSettings.fromMap(properties);
        MetaCredentialsProvider resolved;
        if (credentialsProvider != null) {
            if (parsed.staticCredentials().isPresent()) {
                log.warn("Meta WhatsApp provider: '{}' and '{}' are ignored, the MetaCredentialsProvider {} supplies"
                                + " the credentials", MetaSettings.ACCESS_TOKEN, MetaSettings.PHONE_NUMBER_ID,
                        credentialsProvider.getClass().getSimpleName());
            }
            resolved = credentialsProvider;
        } else {
            MetaCredentials fixed = parsed.staticCredentials().orElseThrow(() -> MetaSettings.invalid("'"
                    + MetaSettings.ACCESS_TOKEN + "' and '" + MetaSettings.PHONE_NUMBER_ID + "' are required, or"
                    + " declare a MetaCredentialsProvider bean"));
            resolved = tenantId -> fixed;
        }
        this.settings = parsed;
        this.credentials = resolved;
        this.initialized = false;
        this.downReason = null;
        log.debug("Meta WhatsApp provider configured: {}", parsed);
    }

    /** Nothing to connect: marks the provider ready. */
    @Override
    public void init() {
        if (settings == null) {
            throw new IllegalStateException("MetaWhatsAppProvider.configure(...) must be called before init()");
        }
        initialized = true;
        log.info("Meta WhatsApp provider initialized: baseUrl={}, apiVersion={}, timeout={}, credentials={},"
                        + " transport={}", settings.baseUrl(), settings.apiVersion(), settings.timeout(),
                credentialSource(), transport.getClass().getSimpleName());
    }

    @Override
    public void destroy() {
        initialized = false;
    }

    /** UP once initialised, DOWN after a rejected credential or a restricted account until a send succeeds. */
    @Override
    public boolean isHealthy() {
        return initialized && downReason == null;
    }

    /** No secrets: the endpoint, the API version, where credentials come from, and why it is DOWN. */
    @Override
    public Map<String, Object> getHealthDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(STATUS, isHealthy() ? "UP" : "DOWN");
        MetaSettings current = settings;
        if (current != null) {
            details.put("baseUrl", current.baseUrl().toString());
            details.put("apiVersion", current.apiVersion());
            details.put("credentials", credentialSource());
        }
        String reason = downReason;
        if (reason != null) {
            details.put("lastError", reason);
        }
        return details;
    }

    @Override
    public SendResult send(NotificationRequest request, RenderedContent content) {
        MetaSettings current = initialized ? settings : null;
        if (current == null) {
            throw new IllegalStateException("MetaWhatsAppProvider is not initialized; call configure(...) and init()");
        }
        if (!(request.getRecipient() instanceof WhatsAppRecipient recipient)
                || recipient.phoneNumber() == null
                || !RECIPIENT.matcher(recipient.phoneNumber().strip()).matches()) {
            return SendResult.failure(MetaErrorCodes.INVALID_RECIPIENT,
                    "the Meta WhatsApp provider needs a WHATSAPP recipient with an E.164 phone number",
                    FailureType.PERMANENT);
        }
        Optional<WhatsAppTemplateMessage> message = content == null
                ? Optional.empty()
                : content.payload(WhatsAppTemplateMessage.class);
        if (message.isEmpty()) {
            return SendResult.failure(MetaErrorCodes.TEMPLATE_REQUIRED,
                    "no WhatsApp template message to send: name a template on the recipient or bind the template id"
                            + " under notification.whatsapp.templates", FailureType.PERMANENT);
        }
        MetaCredentials creds;
        try {
            creds = Objects.requireNonNull(credentials.resolve(request.getTenantId()), "no credentials");
        } catch (RuntimeException e) {
            log.warn("Meta WhatsApp credentials could not be resolved for tenant {}: {}",
                    sanitize(request.getTenantId()), e.getClass().getSimpleName());
            return SendResult.failure(MetaErrorCodes.CREDENTIALS_UNAVAILABLE,
                    "the Meta credentials could not be resolved (" + e.getClass().getSimpleName() + ")",
                    FailureType.TRANSIENT);
        }

        String to = recipient.phoneNumber().strip();
        ObjectNode body = MetaMessageMapper.body(to.startsWith("+") ? to.substring(1) : to, message.get(),
                request.getRequestId());
        MetaHttpRequest httpRequest = new MetaHttpRequest("POST", current.messagesUri(creds.phoneNumberId()),
                Map.of("Authorization", "Bearer " + creds.accessToken(),
                        "Content-Type", "application/json",
                        "Accept", "application/json"),
                MetaJson.writeBytes(body), current.timeout());

        MetaHttpResponse response;
        try {
            response = transport.execute(httpRequest);
        } catch (MetaTransportException e) {
            return failed(request, MetaErrorClassifier.transport(e));
        }
        if (!response.isSuccess()) {
            return failed(request, MetaErrorClassifier.classify(response, current.pairRateLimitRetryAfter(), clock));
        }
        String messageId = MetaJson.readObject(response.body())
                .map(answer -> MetaJson.text(answer.path("messages").path(0), "id"))
                .filter(id -> !id.isBlank())
                .orElse(null);
        if (messageId == null) {
            return failed(request, MetaErrorClassifier.noMessageId(response.statusCode()));
        }
        if (downReason != null) {
            log.info("Meta WhatsApp provider is healthy again after {}", downReason);
            downReason = null;
        }
        log.debug("Meta WhatsApp message accepted: requestId={}, template={}, messageId={}",
                sanitize(request.getRequestId()), message.get().name(), messageId);
        return SendResult.success(messageId);
    }

    private SendResult failed(NotificationRequest request, MetaFailure failure) {
        if (failure.healthDown()) {
            downReason = failure.errorCode();
            log.warn("Meta WhatsApp provider is DOWN until a send succeeds: requestId={}, {}: {}",
                    sanitize(request.getRequestId()), failure.errorCode(), failure.message());
        }
        return SendResult.failure(failure.errorCode(), failure.message(), failure.type(), null, null)
                .withRetryAfter(failure.retryAfter());
    }

    private String credentialSource() {
        return credentialsProvider == null ? "properties" : credentialsProvider.getClass().getSimpleName();
    }

    private static String sanitize(String value) {
        return value == null ? "null" : PiiMasking.redact(value.replaceAll("\\p{Cntrl}", "_"));
    }

    // Package-private accessors for tests.

    MetaSettings settings() {
        return settings;
    }

    MetaHttpTransport transport() {
        return transport;
    }
}
