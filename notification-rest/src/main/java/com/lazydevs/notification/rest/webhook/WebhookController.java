package com.lazydevs.notification.rest.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventListener;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.WebhookProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * REST surface that ingests provider-side delivery callbacks (DD-16).
 *
 * <p>Two paths under {@code ${notification.rest.base-path}/webhooks/}:
 * <ul>
 *   <li>{@code /twilio/status} — Twilio SMS status callback. Form-encoded
 *       body, signed with HMAC-SHA1 over URL + sorted params using the
 *       account auth token.</li>
 *   <li>{@code /ses/sns} — SES delivery / bounce / complaint events
 *       arriving via SNS. JSON envelope, signed with the AWS SNS X.509
 *       scheme (SHA1withRSA or SHA256withRSA depending on
 *       {@code SignatureVersion}).</li>
 * </ul>
 *
 * <p>The whole controller is gated on
 * {@code notification.webhooks.enabled=true}; each per-provider path
 * additionally requires its own enabled flag, returning {@code 404}
 * when the provider isn't configured (rather than {@code 200} with
 * an empty body — operators want a loud signal that the URL they
 * registered with the provider isn't wired up).
 *
 * <p>Failed signature verification returns {@code 403 Forbidden} —
 * see DD-16 §"Why fail-403 rather than fail-silent": a real provider
 * whose signing key rotated will see the 403 and surface the failure
 * in their own admin dashboard; an attacker gets no info beyond
 * "this endpoint exists."
 */
@Slf4j
@RestController
@RequestMapping("${notification.rest.base-path:/api/v1}${notification.webhooks.base-path:/webhooks}")
@ConditionalOnProperty(prefix = "notification.webhooks", name = "enabled", havingValue = "true")
@Tag(name = "Webhooks",
        description = "Provider delivery-callback ingestion (DD-16). "
                + "Each handler verifies the provider's signature scheme "
                + "before parsing the body; failed verification returns "
                + "`403`. Successfully parsed events are dispatched to "
                + "every registered DeliveryEventListener.")
public class WebhookController {

    private final NotificationProperties properties;
    private final List<DeliveryEventListener> listeners;
    private final ObjectMapper json;
    private final SnsSignatureVerifier snsVerifier;
    /**
     * Lazy-initialised — only built when Twilio is enabled and
     * signature verification is on. Storing the verifier keeps the
     * HMAC key initialisation off the request path.
     */
    private volatile TwilioSignatureVerifier twilioVerifier;

    public WebhookController(NotificationProperties properties,
                             List<DeliveryEventListener> listeners) {
        this.properties = properties;
        this.listeners = listeners == null ? List.of() : listeners;
        this.json = new ObjectMapper();
        this.snsVerifier = new SnsSignatureVerifier();
        log.info("WebhookController registered: twilio={} ses={} listeners={}",
                properties.getWebhooks().getTwilio().isEnabled(),
                properties.getWebhooks().getSes().isEnabled(),
                this.listeners.size());
    }

    // -----------------------------------------------------------------
    //  Twilio SMS status callback
    // -----------------------------------------------------------------

    @PostMapping(value = "/twilio/status",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "Twilio status callback (DD-16)",
            description = "Ingest Twilio SMS delivery status. The "
                    + "X-Twilio-Signature header is verified before the "
                    + "body is parsed; failure returns 403.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Event accepted and dispatched."),
                    @ApiResponse(responseCode = "403", description = "Signature verification failed."),
                    @ApiResponse(responseCode = "404", description = "Twilio webhook not enabled.")
            })
    public ResponseEntity<Map<String, Object>> twilioStatus(
            @RequestParam Map<String, String> formParams,
            HttpServletRequest request) {

        WebhookProperties.TwilioWebhook cfg = properties.getWebhooks().getTwilio();
        if (!cfg.isEnabled()) {
            // Not 503 because operators may have intentionally only
            // enabled SES; 404 keeps the URL inert without claiming
            // the path exists.
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "twilio webhook is not enabled"));
        }

        if (cfg.isSignatureVerification()) {
            String signatureB64 = request.getHeader("X-Twilio-Signature");
            if (signatureB64 == null || signatureB64.isBlank()) {
                log.warn("Twilio webhook: missing X-Twilio-Signature");
                return forbidden();
            }
            TwilioSignatureVerifier verifier = twilioVerifier();
            String requestUrl = reconstructRequestUrl(request);
            if (!verifier.verify(requestUrl, formParams, signatureB64)) {
                log.warn("Twilio webhook: signature verification failed for url={} sourceIp={}",
                        sanitize(requestUrl), sanitize(request.getRemoteAddr()));
                return forbidden();
            }
        } else {
            log.warn("Twilio webhook: signature verification is DISABLED — "
                    + "this is fine for dev, dangerous in production");
        }

        // Build event from the form params Twilio sends. Field
        // documentation: https://www.twilio.com/docs/usage/webhooks/sms-webhooks
        DeliveryEvent event = parseTwilioEvent(formParams);
        if (event == null) {
            log.warn("Twilio webhook: missing required fields (MessageSid/MessageStatus)");
            return ResponseEntity.badRequest().body(Map.of("error", "MessageSid and MessageStatus are required"));
        }
        dispatch(event);
        return ResponseEntity.ok(Map.of("status", "accepted"));
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
        // Build a stable event id for idempotent listeners. Twilio
        // doesn't ship a single-shot event id; AccountSid + MessageSid
        // + status is a good enough composite for dedup purposes.
        String accountSid = formParams.getOrDefault("AccountSid", "");
        String eventId = accountSid + ":" + messageSid + ":" + messageStatus;
        return new DeliveryEvent(
                Instant.now(),
                "twilio",
                messageSid,
                eventId,
                status,
                formParams.get("ErrorMessage"),
                attrs);
    }

    static DeliveryStatus mapTwilioStatus(String twilioStatus) {
        if (twilioStatus == null) {
            return DeliveryStatus.UNKNOWN;
        }
        return switch (twilioStatus.toLowerCase(Locale.ROOT)) {
            case "delivered" -> DeliveryStatus.DELIVERED;
            // Twilio "undelivered" + a permanent error code is a true
            // bounce; a soft failure (carrier outage) lands here too,
            // and we accept that some FAILED_AT_PROVIDER cases will
            // class as BOUNCED until per-error-code mapping ships.
            case "undelivered" -> DeliveryStatus.BOUNCED;
            case "failed" -> DeliveryStatus.FAILED_AT_PROVIDER;
            // queued / accepted / sending / sent are intermediate
            // states — the original SendResult already covered the
            // accept moment, so they don't add new info. Map to UNKNOWN
            // and let the listener decide what to do (most will skip).
            default -> DeliveryStatus.UNKNOWN;
        };
    }

    private TwilioSignatureVerifier twilioVerifier() {
        TwilioSignatureVerifier v = twilioVerifier;
        if (v != null) {
            return v;
        }
        synchronized (this) {
            if (twilioVerifier == null) {
                twilioVerifier = new TwilioSignatureVerifier(
                        properties.getWebhooks().getTwilio().getAuthToken());
            }
            return twilioVerifier;
        }
    }

    // -----------------------------------------------------------------
    //  SES delivery / bounce / complaint via SNS
    // -----------------------------------------------------------------

    @PostMapping(value = "/ses/sns",
            consumes = {MediaType.APPLICATION_JSON_VALUE, "text/plain"})
    @Operation(summary = "SES via SNS callback (DD-16)",
            description = "Ingest SES Delivery / Bounce / Complaint "
                    + "events arriving as SNS Notifications. Verifies "
                    + "the SNS X.509 signature; returns 403 on failure. "
                    + "Handles SubscriptionConfirmation envelopes by "
                    + "logging the SubscribeURL — operators confirm "
                    + "the subscription manually.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Event accepted and dispatched."),
                    @ApiResponse(responseCode = "403", description = "Signature verification failed."),
                    @ApiResponse(responseCode = "404", description = "SES webhook not enabled.")
            })
    public ResponseEntity<Map<String, Object>> sesSns(@RequestBody String rawBody) {

        WebhookProperties.SesWebhook cfg = properties.getWebhooks().getSes();
        if (!cfg.isEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "ses webhook is not enabled"));
        }

        JsonNode envelope;
        try {
            envelope = json.readTree(rawBody);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "body is not valid JSON: " + e.getOriginalMessage()));
        }

        if (cfg.isSignatureVerification()) {
            if (!snsVerifier.verify(envelope)) {
                log.warn("SES/SNS webhook: signature verification failed");
                return forbidden();
            }
        } else {
            log.warn("SES/SNS webhook: signature verification is DISABLED — "
                    + "this is fine for dev, dangerous in production");
        }

        // Defense-in-depth: even with a valid signature, drop messages
        // published to a topic ARN we didn't expect. AWS-signed but
        // wrong-topic indicates a misconfigured infra fanout, not a
        // forge.
        String topicArn = textOrNull(envelope, "TopicArn");
        if (cfg.getTopicArn() != null && !cfg.getTopicArn().isBlank()
                && !cfg.getTopicArn().equals(topicArn)) {
            log.warn("SES/SNS webhook: topic arn mismatch — got {}, configured {}",
                    sanitize(topicArn), sanitize(cfg.getTopicArn()));
            return forbidden();
        }

        String type = textOrNull(envelope, "Type");
        if ("SubscriptionConfirmation".equals(type)) {
            // Operators confirm the subscription manually — we don't
            // auto-fetch SubscribeURL because that's a side-effecting
            // GET. Logging the URL is enough; operator pastes it into
            // a browser or the SNS console.
            String subscribeUrl = textOrNull(envelope, "SubscribeURL");
            log.info("SES/SNS webhook: SubscriptionConfirmation received. Operator must confirm: {}",
                    sanitize(subscribeUrl));
            return ResponseEntity.ok(Map.of(
                    "status", "subscription-confirmation-received",
                    "subscribeUrl", subscribeUrl == null ? "" : subscribeUrl));
        }

        if (!"Notification".equals(type)) {
            log.info("SES/SNS webhook: ignoring envelope type={}", sanitize(type));
            return ResponseEntity.ok(Map.of("status", "ignored", "type", type == null ? "" : type));
        }

        // Notification.Message is a JSON string — a separate parse step.
        String inner = textOrNull(envelope, "Message");
        DeliveryEvent event = parseSesMessage(inner);
        if (event == null) {
            log.warn("SES/SNS webhook: could not parse Notification.Message — dropping");
            return ResponseEntity.badRequest().body(Map.of("error", "could not parse Notification.Message"));
        }
        dispatch(event);
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    DeliveryEvent parseSesMessage(String inner) {
        if (inner == null) {
            return null;
        }
        try {
            JsonNode msg = json.readTree(inner);
            String notificationType = textOrNull(msg, "notificationType");
            if (notificationType == null) {
                return null;
            }
            // SES notification shapes:
            //   Delivery: mail.messageId + delivery.timestamp
            //   Bounce:   mail.messageId + bounce.bounceType
            //   Complaint:mail.messageId + complaint.timestamp
            JsonNode mail = msg.get("mail");
            String providerMessageId = mail != null ? textOrNull(mail, "messageId") : null;
            if (providerMessageId == null) {
                return null;
            }
            DeliveryStatus status;
            String reason = null;
            switch (notificationType) {
                case "Delivery" -> status = DeliveryStatus.DELIVERED;
                case "Bounce" -> {
                    JsonNode b = msg.get("bounce");
                    String bounceType = b != null ? textOrNull(b, "bounceType") : null;
                    status = "Permanent".equalsIgnoreCase(bounceType)
                            ? DeliveryStatus.BOUNCED
                            : DeliveryStatus.FAILED_AT_PROVIDER;
                    reason = b != null ? textOrNull(b, "bounceSubType") : null;
                }
                case "Complaint" -> {
                    status = DeliveryStatus.COMPLAINED;
                    JsonNode c = msg.get("complaint");
                    reason = c != null ? textOrNull(c, "complaintFeedbackType") : null;
                }
                default -> status = DeliveryStatus.UNKNOWN;
            }
            String providerEventId = textOrNull(msg, "notificationType")
                    + ":" + providerMessageId
                    + ":" + (mail != null ? textOrNull(mail, "timestamp") : "");
            Map<String, String> attrs = new HashMap<>();
            attrs.put("notificationtype", notificationType);
            return new DeliveryEvent(
                    Instant.now(),
                    "ses",
                    providerMessageId,
                    providerEventId,
                    status,
                    reason,
                    attrs);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("SES inner message is not valid JSON: {}", e.getOriginalMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private void dispatch(DeliveryEvent event) {
        for (DeliveryEventListener l : listeners) {
            try {
                l.onEvent(event);
            } catch (RuntimeException e) {
                // SPI contract is "must not throw" — but trust nothing,
                // catch and log so a bad listener can't break the
                // webhook handler. Provider gets a 200 either way.
                log.warn("DeliveryEventListener {} threw — continuing: {}",
                        l.getClass().getSimpleName(), e.toString());
            }
        }
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "signature verification failed"));
    }

    /**
     * Reconstruct the URL the provider used. Twilio computes its HMAC
     * over the URL it called, including query string. Behind a proxy
     * this URL must match what the proxy registered with Twilio —
     * operators terminating TLS at a proxy may need to set
     * {@code X-Forwarded-Proto} / {@code X-Forwarded-Host} on the
     * proxy and make Spring Boot honour them
     * ({@code server.forward-headers-strategy=framework}).
     */
    static String reconstructRequestUrl(HttpServletRequest request) {
        StringBuffer url = request.getRequestURL();
        if (request.getQueryString() != null) {
            url.append('?').append(request.getQueryString());
        }
        return url.toString();
    }

    private static String textOrNull(JsonNode root, String name) {
        if (root == null) {
            return null;
        }
        JsonNode n = root.get(name);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static String sanitize(String s) {
        return s == null ? "null" : s.replaceAll("[\\p{Cntrl}]", "_");
    }
}
