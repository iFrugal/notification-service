package com.lazydevs.notification.rest.webhook;

import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventListener;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.core.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC tests for the DD-16 webhook surface.
 *
 * <p>The Twilio HMAC and SNS X.509 algorithms are unit-tested
 * separately (`TwilioSignatureVerifierTest`, `SnsSignatureVerifierTest`)
 * — here we focus on the controller's routing, gating, parsing, and
 * dispatch behaviour. Signature verification is exercised against the
 * real {@link TwilioSignatureVerifier}; the SNS path is covered with
 * verification disabled (boolean-toggle path) so we don't need to
 * generate a test X.509 keypair.
 */
class WebhookControllerTest {

    private NotificationProperties properties;
    private RecordingListener listener;
    private WebhookController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.getWebhooks().setEnabled(true);
        listener = new RecordingListener();
        controller = new WebhookController(properties, List.of(listener));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // -----------------------------------------------------------------
    //  Twilio
    // -----------------------------------------------------------------

    @Test
    void twilio_disabled_returns404() throws Exception {
        properties.getWebhooks().getTwilio().setEnabled(false);
        mockMvc.perform(post("/api/v1/webhooks/twilio/status")
                        .contentType("application/x-www-form-urlencoded")
                        .content("MessageSid=SM1&MessageStatus=delivered"))
                .andExpect(status().isNotFound());
        assertThat(listener.events).isEmpty();
    }

    @Test
    void twilio_missingSignatureHeader_returns403() throws Exception {
        properties.getWebhooks().getTwilio().setEnabled(true);
        properties.getWebhooks().getTwilio().setAuthToken("tok-abc");
        properties.getWebhooks().getTwilio().setSignatureVerification(true);

        mockMvc.perform(post("/api/v1/webhooks/twilio/status")
                        .contentType("application/x-www-form-urlencoded")
                        .content("MessageSid=SM1&MessageStatus=delivered"))
                .andExpect(status().isForbidden());
        assertThat(listener.events).isEmpty();
    }

    @Test
    void twilio_validSignature_dispatchesEvent() throws Exception {
        properties.getWebhooks().getTwilio().setEnabled(true);
        properties.getWebhooks().getTwilio().setAuthToken("tok-abc");
        properties.getWebhooks().getTwilio().setSignatureVerification(true);

        // Compute the signature against the URL MockMvc reconstructs
        // (default standalone setup → http://localhost/api/v1/webhooks/twilio/status).
        String url = "http://localhost/api/v1/webhooks/twilio/status";
        Map<String, String> params = Map.of(
                "MessageSid", "SM1",
                "MessageStatus", "delivered",
                "AccountSid", "AC1",
                "To", "+15558675309",
                "From", "+15551234567");
        String sig = new TwilioSignatureVerifier("tok-abc").computeSignature(url, params);

        mockMvc.perform(post("/api/v1/webhooks/twilio/status")
                        .header("X-Twilio-Signature", sig)
                        .contentType("application/x-www-form-urlencoded")
                        .content("MessageSid=SM1&MessageStatus=delivered&AccountSid=AC1"
                                + "&To=%2B15558675309&From=%2B15551234567"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        assertThat(listener.events).hasSize(1);
        DeliveryEvent ev = listener.events.get(0);
        assertThat(ev.providerName()).isEqualTo("twilio");
        assertThat(ev.providerMessageId()).isEqualTo("SM1");
        assertThat(ev.status()).isEqualTo(DeliveryStatus.DELIVERED);
        // The composite event id is AccountSid:MessageSid:Status — used
        // by listeners as the dedup key.
        assertThat(ev.providerEventId()).isEqualTo("AC1:SM1:delivered");
        // Attributes are lowercase-keyed for case-stable lookup.
        assertThat(ev.attributes()).containsKey("messagesid");
    }

    @Test
    void twilio_signatureVerificationDisabled_acceptsAnyBody() throws Exception {
        properties.getWebhooks().getTwilio().setEnabled(true);
        properties.getWebhooks().getTwilio().setSignatureVerification(false);
        // No auth token provided — the verifier shouldn't be built when
        // verification is off.
        mockMvc.perform(post("/api/v1/webhooks/twilio/status")
                        .contentType("application/x-www-form-urlencoded")
                        .content("MessageSid=SM2&MessageStatus=undelivered"))
                .andExpect(status().isOk());

        assertThat(listener.events).hasSize(1);
        assertThat(listener.events.get(0).status()).isEqualTo(DeliveryStatus.BOUNCED);
    }

    @Test
    void twilio_missingRequiredFields_returns400() throws Exception {
        properties.getWebhooks().getTwilio().setEnabled(true);
        properties.getWebhooks().getTwilio().setSignatureVerification(false);

        mockMvc.perform(post("/api/v1/webhooks/twilio/status")
                        .contentType("application/x-www-form-urlencoded")
                        .content("AccountSid=AC1"))  // no MessageSid / MessageStatus
                .andExpect(status().isBadRequest());

        assertThat(listener.events).isEmpty();
    }

    @Test
    void twilio_statusMappings() {
        // Static helper — verify the mapping table directly so a
        // future maintainer changing the cases doesn't have to spin
        // up MockMvc to know the contract.
        assertThat(WebhookController.mapTwilioStatus("delivered"))
                .isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(WebhookController.mapTwilioStatus("DELIVERED"))
                .isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(WebhookController.mapTwilioStatus("undelivered"))
                .isEqualTo(DeliveryStatus.BOUNCED);
        assertThat(WebhookController.mapTwilioStatus("failed"))
                .isEqualTo(DeliveryStatus.FAILED_AT_PROVIDER);
        assertThat(WebhookController.mapTwilioStatus("queued"))
                .isEqualTo(DeliveryStatus.UNKNOWN);
        assertThat(WebhookController.mapTwilioStatus(null))
                .isEqualTo(DeliveryStatus.UNKNOWN);
    }

    // -----------------------------------------------------------------
    //  SES via SNS
    // -----------------------------------------------------------------

    @Test
    void ses_disabled_returns404() throws Exception {
        properties.getWebhooks().getSes().setEnabled(false);
        mockMvc.perform(post("/api/v1/webhooks/ses/sns")
                        .contentType("application/json")
                        .content("{\"Type\":\"Notification\"}"))
                .andExpect(status().isNotFound());
        assertThat(listener.events).isEmpty();
    }

    @Test
    void ses_subscriptionConfirmation_logsAndAcks() throws Exception {
        properties.getWebhooks().getSes().setEnabled(true);
        properties.getWebhooks().getSes().setSignatureVerification(false);

        String body = """
                {
                  "Type": "SubscriptionConfirmation",
                  "MessageId": "sub-1",
                  "TopicArn": "arn:aws:sns:us-east-1:000:my-topic",
                  "SubscribeURL": "https://sns.us-east-1.amazonaws.com/?Action=ConfirmSubscription&Token=abc",
                  "Token": "abc",
                  "Timestamp": "2026-04-30T12:00:00.000Z",
                  "Message": "You have chosen to subscribe..."
                }
                """;

        mockMvc.perform(post("/api/v1/webhooks/ses/sns")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("subscription-confirmation-received"))
                .andExpect(jsonPath("$.subscribeUrl").value(
                        "https://sns.us-east-1.amazonaws.com/?Action=ConfirmSubscription&Token=abc"));

        // Subscription confirmation is operator-handled, not a delivery
        // event — listeners should not be invoked.
        assertThat(listener.events).isEmpty();
    }

    @Test
    void ses_notification_dispatchesDeliveryEvent() throws Exception {
        properties.getWebhooks().getSes().setEnabled(true);
        properties.getWebhooks().getSes().setSignatureVerification(false);

        String inner = "{\"notificationType\":\"Delivery\",\"mail\":{"
                + "\"messageId\":\"ses-msg-1\",\"timestamp\":\"2026-04-30T12:00:00.000Z\""
                + "},\"delivery\":{\"timestamp\":\"2026-04-30T12:00:01.000Z\"}}";
        String body = "{\"Type\":\"Notification\",\"MessageId\":\"sns-1\","
                + "\"TopicArn\":\"arn:aws:sns:us-east-1:000:my-topic\","
                + "\"Message\":" + escapeForJson(inner) + ","
                + "\"Timestamp\":\"2026-04-30T12:00:02.000Z\"}";

        mockMvc.perform(post("/api/v1/webhooks/ses/sns")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        assertThat(listener.events).hasSize(1);
        DeliveryEvent ev = listener.events.get(0);
        assertThat(ev.providerName()).isEqualTo("ses");
        assertThat(ev.providerMessageId()).isEqualTo("ses-msg-1");
        assertThat(ev.status()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test
    void ses_permanentBounce_mapsToBOUNCED() throws Exception {
        properties.getWebhooks().getSes().setEnabled(true);
        properties.getWebhooks().getSes().setSignatureVerification(false);

        String inner = "{\"notificationType\":\"Bounce\",\"mail\":{"
                + "\"messageId\":\"ses-msg-2\"},"
                + "\"bounce\":{\"bounceType\":\"Permanent\",\"bounceSubType\":\"NoEmail\"}}";
        String body = "{\"Type\":\"Notification\",\"MessageId\":\"sns-2\","
                + "\"TopicArn\":\"arn:aws:sns:us-east-1:000:my-topic\","
                + "\"Message\":" + escapeForJson(inner) + "}";

        mockMvc.perform(post("/api/v1/webhooks/ses/sns")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        assertThat(listener.events).hasSize(1);
        DeliveryEvent ev = listener.events.get(0);
        assertThat(ev.status()).isEqualTo(DeliveryStatus.BOUNCED);
        assertThat(ev.reason()).isEqualTo("NoEmail");
    }

    @Test
    void ses_transientBounce_mapsToFailedAtProvider() throws Exception {
        properties.getWebhooks().getSes().setEnabled(true);
        properties.getWebhooks().getSes().setSignatureVerification(false);

        // Soft bounce — not a true BOUNCED, the recipient address
        // might still be valid. FAILED_AT_PROVIDER is the right
        // classification.
        String inner = "{\"notificationType\":\"Bounce\",\"mail\":{"
                + "\"messageId\":\"ses-msg-3\"},"
                + "\"bounce\":{\"bounceType\":\"Transient\",\"bounceSubType\":\"MailboxFull\"}}";
        String body = "{\"Type\":\"Notification\","
                + "\"TopicArn\":\"arn:aws:sns:us-east-1:000:my-topic\","
                + "\"Message\":" + escapeForJson(inner) + "}";

        mockMvc.perform(post("/api/v1/webhooks/ses/sns")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        assertThat(listener.events).hasSize(1);
        assertThat(listener.events.get(0).status()).isEqualTo(DeliveryStatus.FAILED_AT_PROVIDER);
    }

    @Test
    void ses_complaint_mapsToCOMPLAINED() throws Exception {
        properties.getWebhooks().getSes().setEnabled(true);
        properties.getWebhooks().getSes().setSignatureVerification(false);

        String inner = "{\"notificationType\":\"Complaint\",\"mail\":{"
                + "\"messageId\":\"ses-msg-4\"},"
                + "\"complaint\":{\"complaintFeedbackType\":\"abuse\"}}";
        String body = "{\"Type\":\"Notification\","
                + "\"TopicArn\":\"arn:aws:sns:us-east-1:000:my-topic\","
                + "\"Message\":" + escapeForJson(inner) + "}";

        mockMvc.perform(post("/api/v1/webhooks/ses/sns")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        assertThat(listener.events).hasSize(1);
        DeliveryEvent ev = listener.events.get(0);
        assertThat(ev.status()).isEqualTo(DeliveryStatus.COMPLAINED);
        assertThat(ev.reason()).isEqualTo("abuse");
    }

    @Test
    void ses_topicArnMismatch_returns403() throws Exception {
        properties.getWebhooks().getSes().setEnabled(true);
        properties.getWebhooks().getSes().setSignatureVerification(false);
        properties.getWebhooks().getSes().setTopicArn("arn:aws:sns:us-east-1:000:expected-topic");

        // Even with verification disabled (i.e. nothing to forge yet),
        // a wrong topic ARN should be rejected — defense-in-depth: an
        // accidental fan-out from the wrong topic shouldn't update
        // delivery state.
        String body = "{\"Type\":\"Notification\","
                + "\"TopicArn\":\"arn:aws:sns:us-east-1:000:OTHER-TOPIC\","
                + "\"Message\":\"{}\"}";

        mockMvc.perform(post("/api/v1/webhooks/ses/sns")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden());
        assertThat(listener.events).isEmpty();
    }

    @Test
    void ses_invalidJsonBody_returns400() throws Exception {
        properties.getWebhooks().getSes().setEnabled(true);
        properties.getWebhooks().getSes().setSignatureVerification(false);

        mockMvc.perform(post("/api/v1/webhooks/ses/sns")
                        .contentType("application/json")
                        .content("{ not json"))
                .andExpect(status().isBadRequest());
        assertThat(listener.events).isEmpty();
    }

    @Test
    void ses_unknownEnvelopeType_returns200WithIgnored() throws Exception {
        properties.getWebhooks().getSes().setEnabled(true);
        properties.getWebhooks().getSes().setSignatureVerification(false);

        mockMvc.perform(post("/api/v1/webhooks/ses/sns")
                        .contentType("application/json")
                        .content("{\"Type\":\"WeirdEnvelope\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));
        assertThat(listener.events).isEmpty();
    }

    @Test
    void listenerThrows_otherListenersStillFireAndProviderGets200() throws Exception {
        properties.getWebhooks().getTwilio().setEnabled(true);
        properties.getWebhooks().getTwilio().setSignatureVerification(false);

        // Two listeners — first throws, second records. The webhook
        // contract is "200 once we received it"; a buggy listener
        // shouldn't make us return 500 to the provider (which would
        // then retry forever).
        ThrowingListener throwing = new ThrowingListener();
        controller = new WebhookController(properties, List.of(throwing, listener));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/api/v1/webhooks/twilio/status")
                        .contentType("application/x-www-form-urlencoded")
                        .content("MessageSid=SM3&MessageStatus=delivered"))
                .andExpect(status().isOk());

        assertThat(throwing.calls).isEqualTo(1);
        assertThat(listener.events).hasSize(1);
    }

    // -----------------------------------------------------------------
    //  Test fixtures
    // -----------------------------------------------------------------

    private static String escapeForJson(String s) {
        // Wrap in JSON-string form: turn into a JSON literal that, when
        // re-parsed, yields the original string. Jackson does this
        // properly via writeValueAsString; doing it inline keeps tests
        // dependency-free.
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private static final class RecordingListener implements DeliveryEventListener {
        final List<DeliveryEvent> events = new ArrayList<>();

        @Override
        public void onEvent(DeliveryEvent event) {
            events.add(event);
        }
    }

    private static final class ThrowingListener implements DeliveryEventListener {
        int calls;

        @Override
        public void onEvent(DeliveryEvent event) {
            calls++;
            throw new RuntimeException("simulated listener failure");
        }
    }
}
