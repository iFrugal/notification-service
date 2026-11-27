package com.lazydevs.notification.rest.webhook;

import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.webhook.WebhookHandler;
import com.lazydevs.notification.api.webhook.WebhookRequest;
import com.lazydevs.notification.api.webhook.WebhookResponse;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.WebhookProperties;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Twilio SMS status callback, {@code POST <webhooks base path>/twilio/status}
 * (DD-16, DD-36).
 *
 * <p>Form-encoded body, signed with HMAC-SHA1 over the URL Twilio called plus
 * the sorted form parameters, keyed with the account auth token
 * ({@code X-Twilio-Signature}). Configured by
 * {@code notification.webhooks.twilio.*}; answers 404 while
 * {@code enabled=false}, 403 on a failed signature, 400 without
 * {@code MessageSid} and {@code MessageStatus}, and 200
 * {@code {"status":"accepted"}} with one event otherwise.
 *
 * @since 1.2.2 (the same endpoint was a controller method before)
 */
@Slf4j
public class TwilioWebhookHandler implements WebhookHandler {

    /** Handler id and provider name. */
    public static final String HANDLER_ID = "twilio";

    /** Path below the handler id. */
    public static final String STATUS_PATH = "status";

    static final String SIGNATURE_HEADER = "X-Twilio-Signature";

    private final NotificationProperties properties;

    /**
     * Lazily built: only when Twilio is enabled with signature verification
     * on, which keeps the HMAC key initialisation off startup when unused.
     */
    private volatile TwilioSignatureVerifier verifier;

    public TwilioWebhookHandler(NotificationProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public String id() {
        return HANDLER_ID;
    }

    @Override
    public WebhookResponse handle(WebhookRequest request) {
        WebhookResponse misrouted = WebhookBodies.route(request, STATUS_PATH, "application/x-www-form-urlencoded");
        if (misrouted != null) {
            return misrouted;
        }
        WebhookProperties.TwilioWebhook cfg = properties.getWebhooks().getTwilio();
        if (!cfg.isEnabled()) {
            // Not 503 because operators may have intentionally only enabled
            // SES; 404 keeps the URL inert without claiming the path exists.
            return WebhookBodies.error(404, "twilio webhook is not enabled");
        }

        Map<String, String> formParams = WebhookBodies.form(request.bodyAsString());
        if (cfg.isSignatureVerification()) {
            String signatureB64 = request.header(SIGNATURE_HEADER);
            if (signatureB64 == null || signatureB64.isBlank()) {
                log.warn("Twilio webhook: missing X-Twilio-Signature");
                return WebhookBodies.signatureFailed();
            }
            String requestUrl = request.requestUrl();
            if (requestUrl == null || !verifier().verify(requestUrl, formParams, signatureB64)) {
                log.warn("Twilio webhook: signature verification failed for url={}",
                        WebhookBodies.sanitize(requestUrl));
                return WebhookBodies.signatureFailed();
            }
        } else {
            log.warn("Twilio webhook: signature verification is DISABLED - "
                    + "this is fine for dev, dangerous in production");
        }

        // Field documentation: https://www.twilio.com/docs/usage/webhooks/sms-webhooks
        DeliveryEvent event = parseTwilioEvent(formParams);
        if (event == null) {
            log.warn("Twilio webhook: missing required fields (MessageSid/MessageStatus)");
            return WebhookBodies.error(400, "MessageSid and MessageStatus are required");
        }
        return WebhookBodies.accepted(event);
    }

    static DeliveryEvent parseTwilioEvent(Map<String, String> formParams) {
        String messageSid = formParams.get("MessageSid");
        String messageStatus = formParams.get("MessageStatus");
        if (messageSid == null || messageStatus == null) {
            return null;
        }
        DeliveryStatus status = mapTwilioStatus(messageStatus);
        Map<String, String> attrs = new LinkedHashMap<>();
        formParams.forEach((k, v) -> attrs.put(k.toLowerCase(Locale.ROOT), v));
        // Build a stable event id for idempotent listeners. Twilio doesn't
        // ship a single-shot event id; AccountSid + MessageSid + status is a
        // good enough composite for dedup purposes.
        String accountSid = formParams.getOrDefault("AccountSid", "");
        String eventId = accountSid + ":" + messageSid + ":" + messageStatus;
        return new DeliveryEvent(
                Instant.now(),
                HANDLER_ID,
                messageSid,
                eventId,
                status,
                formParams.get("ErrorMessage"),
                attrs);
    }

    /**
     * Map a Twilio message status.
     *
     * <p>Twilio's {@code undelivered} with a permanent error code is a true
     * bounce. A soft failure, such as a carrier outage, is reported the same
     * way, so some provider-side failures class as BOUNCED until a mapping per
     * error code ships. The intermediate states (queued, accepted, sending,
     * sent) add nothing to the send result, so they are UNKNOWN and the
     * listener decides.
     */
    static DeliveryStatus mapTwilioStatus(String twilioStatus) {
        if (twilioStatus == null) {
            return DeliveryStatus.UNKNOWN;
        }
        return switch (twilioStatus.toLowerCase(Locale.ROOT)) {
            case "delivered" -> DeliveryStatus.DELIVERED;
            case "undelivered" -> DeliveryStatus.BOUNCED;
            case "failed" -> DeliveryStatus.FAILED_AT_PROVIDER;
            default -> DeliveryStatus.UNKNOWN;
        };
    }

    private TwilioSignatureVerifier verifier() {
        TwilioSignatureVerifier v = verifier;
        if (v != null) {
            return v;
        }
        synchronized (this) {
            if (verifier == null) {
                verifier = new TwilioSignatureVerifier(properties.getWebhooks().getTwilio().getAuthToken());
            }
            return verifier;
        }
    }
}
