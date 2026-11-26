package com.lazydevs.notification.rest.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.webhook.WebhookHandler;
import com.lazydevs.notification.api.webhook.WebhookRequest;
import com.lazydevs.notification.api.webhook.WebhookResponse;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.WebhookProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * SES delivery, bounce and complaint events arriving through SNS,
 * {@code POST <webhooks base path>/ses/sns} (DD-16, DD-36).
 *
 * <p>JSON envelope signed with the AWS SNS X.509 scheme (SHA1withRSA or
 * SHA256withRSA depending on {@code SignatureVersion}). Configured by
 * {@code notification.webhooks.ses.*}; answers 404 while
 * {@code enabled=false}, 400 for a body that is not JSON, 403 on a failed
 * signature or an unexpected topic ARN. A {@code SubscriptionConfirmation} is
 * logged for an operator to confirm, never fetched.
 *
 * @since 1.4.0 (the same endpoint was a controller method before)
 */
@Slf4j
public class SesWebhookHandler implements WebhookHandler {

    /** Handler id and provider name. */
    public static final String HANDLER_ID = "ses";

    /** Path below the handler id. */
    public static final String SNS_PATH = "sns";

    private static final String FIELD_TYPE = "type";

    private final NotificationProperties properties;
    private final ObjectMapper json = new ObjectMapper();
    private final SnsSignatureVerifier snsVerifier;

    public SesWebhookHandler(NotificationProperties properties) {
        this(properties, new SnsSignatureVerifier());
    }

    SesWebhookHandler(NotificationProperties properties, SnsSignatureVerifier snsVerifier) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.snsVerifier = Objects.requireNonNull(snsVerifier, "snsVerifier");
    }

    @Override
    public String id() {
        return HANDLER_ID;
    }

    @Override
    public WebhookResponse handle(WebhookRequest request) {
        WebhookResponse misrouted = WebhookBodies.route(request, SNS_PATH, "application/json", "text/plain");
        if (misrouted != null) {
            return misrouted;
        }
        WebhookProperties.SesWebhook cfg = properties.getWebhooks().getSes();
        if (!cfg.isEnabled()) {
            return WebhookBodies.error(404, "ses webhook is not enabled");
        }
        JsonNode envelope;
        try {
            envelope = readEnvelope(request.body());
        } catch (IllegalArgumentException e) {
            return WebhookBodies.error(400, "body is not valid JSON: " + e.getMessage());
        }
        WebhookResponse rejected = verify(cfg, envelope);
        if (rejected != null) {
            return rejected;
        }
        return dispatchEnvelope(envelope);
    }

    /**
     * @throws IllegalArgumentException with the parse problem when the body is not JSON or empty
     */
    private JsonNode readEnvelope(byte[] body) {
        JsonNode envelope;
        try {
            envelope = json.readTree(body);
        } catch (IOException e) {
            String reason = e instanceof JsonProcessingException jpe ? jpe.getOriginalMessage() : e.getMessage();
            throw new IllegalArgumentException(reason, e);
        }
        if (envelope == null || envelope.isMissingNode()) {
            throw new IllegalArgumentException("empty body");
        }
        return envelope;
    }

    /** @return the 403 answer when the signature or the topic is wrong, else {@code null} */
    private WebhookResponse verify(WebhookProperties.SesWebhook cfg, JsonNode envelope) {
        if (cfg.isSignatureVerification()) {
            if (!snsVerifier.verify(envelope)) {
                log.warn("SES/SNS webhook: signature verification failed");
                return WebhookBodies.signatureFailed();
            }
        } else {
            log.warn("SES/SNS webhook: signature verification is DISABLED - "
                    + "this is fine for dev, dangerous in production");
        }

        // Defense-in-depth: even with a valid signature, drop messages
        // published to a topic ARN we didn't expect. AWS-signed but
        // wrong-topic indicates a misconfigured infra fanout, not a forge.
        String topicArn = textOrNull(envelope, "TopicArn");
        if (cfg.getTopicArn() != null && !cfg.getTopicArn().isBlank()
                && !cfg.getTopicArn().equals(topicArn)) {
            log.warn("SES/SNS webhook: topic arn mismatch - got {}, configured {}",
                    WebhookBodies.sanitize(topicArn), WebhookBodies.sanitize(cfg.getTopicArn()));
            return WebhookBodies.signatureFailed();
        }
        return null;
    }

    private WebhookResponse dispatchEnvelope(JsonNode envelope) {
        String type = textOrNull(envelope, "Type");
        if ("SubscriptionConfirmation".equals(type)) {
            // Operators confirm the subscription manually - we don't
            // auto-fetch SubscribeURL because that's a side-effecting GET.
            String subscribeUrl = textOrNull(envelope, "SubscribeURL");
            log.info("SES/SNS webhook: SubscriptionConfirmation received. Operator must confirm: {}",
                    WebhookBodies.sanitize(subscribeUrl));
            return WebhookResponse.json(200, WebhookBodies.object(
                    WebhookBodies.FIELD_STATUS, "subscription-confirmation-received",
                    "subscribeUrl", subscribeUrl == null ? "" : subscribeUrl));
        }

        if (!"Notification".equals(type)) {
            log.info("SES/SNS webhook: ignoring envelope type={}", WebhookBodies.sanitize(type));
            return WebhookResponse.json(200, WebhookBodies.object(
                    WebhookBodies.FIELD_STATUS, "ignored", FIELD_TYPE, type == null ? "" : type));
        }

        // Notification.Message is a JSON string - a separate parse step.
        DeliveryEvent event = parseSesMessage(textOrNull(envelope, "Message"));
        if (event == null) {
            log.warn("SES/SNS webhook: could not parse Notification.Message - dropping");
            return WebhookBodies.error(400, "could not parse Notification.Message");
        }
        return WebhookBodies.accepted(event);
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
            String providerEventId = notificationType
                    + ":" + providerMessageId
                    + ":" + (mail != null ? textOrNull(mail, "timestamp") : "");
            Map<String, String> attrs = new HashMap<>();
            attrs.put("notificationtype", notificationType);
            return new DeliveryEvent(
                    Instant.now(),
                    HANDLER_ID,
                    providerMessageId,
                    providerEventId,
                    status,
                    reason,
                    attrs);
        } catch (JsonProcessingException e) {
            log.warn("SES inner message is not valid JSON: {}", e.getOriginalMessage());
            return null;
        }
    }

    private static String textOrNull(JsonNode root, String name) {
        if (root == null) {
            return null;
        }
        JsonNode n = root.get(name);
        return n == null || n.isNull() ? null : n.asText();
    }
}
