package com.lazydevs.notification.channel.whatsapp.meta;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.webhook.WebhookRequest;
import com.lazydevs.notification.api.webhook.WebhookResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetaWebhookHandlerTest {

    private static final String SECRET = "your-app-secret";
    private static final String VERIFY_TOKEN = "your-verify-token";
    private static final String RECIPIENT = "15550100001";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(MetaWebhookHandler.class);
    private MetaWebhookHandler handler;

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        handler = new MetaWebhookHandler(properties(true), CLOCK);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private static MetaWebhookProperties properties(boolean signatureVerification) {
        MetaWebhookProperties properties = new MetaWebhookProperties();
        properties.setEnabled(true);
        properties.setAppSecret(SECRET);
        properties.setVerifyToken(VERIFY_TOKEN);
        properties.setSignatureVerification(signatureVerification);
        return properties;
    }

    static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static WebhookRequest get(Map<String, List<String>> query) {
        return new WebhookRequest("GET", "", Map.of(), query, null, null);
    }

    private static WebhookRequest post(String body, String signature) {
        Map<String, List<String>> headers = signature == null ? Map.of()
                : Map.of("x-hub-signature-256", List.of(signature));
        return new WebhookRequest("POST", "", headers, Map.of(), body.getBytes(StandardCharsets.UTF_8), null);
    }

    private WebhookResponse signedPost(String body) throws Exception {
        return handler.handle(post(body, sign(body, SECRET)));
    }

    static String statusPayload(String statuses) {
        return "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"200000000000002\",\"changes\":[{"
                + "\"field\":\"messages\",\"value\":{\"messaging_product\":\"whatsapp\","
                + "\"metadata\":{\"display_phone_number\":\"15550100000\",\"phone_number_id\":\"100000000000001\"},"
                + "\"statuses\":[" + statuses + "]}}]}]}";
    }

    static String status(String wamid, String status, String timestamp, String extra) {
        return "{\"id\":\"" + wamid + "\",\"status\":\"" + status + "\",\"timestamp\":\"" + timestamp + "\","
                + "\"recipient_id\":\"" + RECIPIENT + "\",\"biz_opaque_callback_data\":\"req-1\"" + extra + "}";
    }

    // ---------------------------------------------------------------- handshake

    @Test
    void handshake_withTheVerifyToken_echoesTheChallenge() {
        WebhookResponse response = handler.handle(get(Map.of("hub.mode", List.of("subscribe"),
                "hub.verify_token", List.of(VERIFY_TOKEN), "hub.challenge", List.of("1158201444"))));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.contentType()).isEqualTo(WebhookResponse.TEXT_PLAIN);
        assertThat(response.body()).isEqualTo("1158201444");
        assertThat(response.events()).isEmpty();
    }

    @Test
    void handshake_withAWrongTokenModeOrNoChallenge_is403() {
        List<Map<String, List<String>>> bad = List.of(
                Map.of("hub.mode", List.of("subscribe"), "hub.verify_token", List.of("wrong"),
                        "hub.challenge", List.of("1")),
                Map.of("hub.mode", List.of("unsubscribe"), "hub.verify_token", List.of(VERIFY_TOKEN),
                        "hub.challenge", List.of("1")),
                Map.of("hub.mode", List.of("subscribe"), "hub.verify_token", List.of(VERIFY_TOKEN)),
                Map.of());
        for (Map<String, List<String>> query : bad) {
            WebhookResponse response = handler.handle(get(query));
            assertThat(response.status()).isEqualTo(403);
            assertThat(response.signatureRejected()).isTrue();
        }
    }

    @Test
    void handshake_withoutAConfiguredVerifyToken_isAlways403() {
        MetaWebhookProperties properties = properties(true);
        properties.setVerifyToken(null);
        MetaWebhookHandler noToken = new MetaWebhookHandler(properties, CLOCK);

        assertThat(noToken.handle(get(Map.of("hub.mode", List.of("subscribe"), "hub.verify_token", List.of(""),
                "hub.challenge", List.of("1")))).status()).isEqualTo(403);
    }

    // ---------------------------------------------------------------- signature

    @Test
    void signature_missingWrongOrMalformed_is403_withNoEvents() throws Exception {
        String body = statusPayload(status("wamid.A", "delivered", "1767225600", ""));

        for (String signature : new String[] {null, sign(body, "other-secret"), "sha1=abc", "sha256=zz",
                sign(body + " ", SECRET)}) {
            WebhookResponse response = handler.handle(post(body, signature));
            assertThat(response.status()).isEqualTo(403);
            assertThat(response.signatureRejected()).isTrue();
            assertThat(response.events()).isEmpty();
        }
    }

    @Test
    void signature_valid_isCaseInsensitiveOnTheHex() throws Exception {
        String body = statusPayload(status("wamid.A", "delivered", "1767225600", ""));
        String upper = "sha256=" + sign(body, SECRET).substring("sha256=".length()).toUpperCase();

        assertThat(handler.handle(post(body, upper)).status()).isEqualTo(200);
    }

    @Test
    void signatureVerificationOff_acceptsUnsignedBodies_andWarns() {
        MetaWebhookProperties properties = properties(false);
        properties.setAppSecret(null);
        MetaWebhookHandler unverified = new MetaWebhookHandler(properties, CLOCK);

        WebhookResponse response = unverified.handle(post(statusPayload(status("wamid.A", "sent", "1", "")), null));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.events()).hasSize(1);
        assertThat(logs.list).anyMatch(e -> e.getFormattedMessage().contains("signature verification is DISABLED"));
    }

    @Test
    void construction_requiresTheSecretWhileVerifying_andAValidId() {
        MetaWebhookProperties noSecret = properties(true);
        noSecret.setAppSecret(" ");
        MetaWebhookProperties badId = properties(true);
        badId.setId("Meta/App");

        assertThatThrownBy(() -> new MetaWebhookHandler(noSecret)).hasMessageContaining("app-secret");
        assertThatThrownBy(() -> new MetaWebhookHandler(badId)).hasMessageContaining("id");
        MetaWebhookProperties second = properties(true);
        second.setId("meta-second-app");
        assertThat(new MetaWebhookHandler(second).id()).isEqualTo("meta-second-app");
    }

    // ---------------------------------------------------------------- statuses

    @Test
    void eachStatus_mapsOntoADeliveryEvent() throws Exception {
        String body = statusPayload(String.join(",",
                status("wamid.A", "sent", "1767225600", ",\"pricing\":{\"billable\":true,\"category\":\"utility\"},"
                        + "\"conversation\":{\"id\":\"c1\",\"origin\":{\"type\":\"utility\"}}"),
                status("wamid.A", "delivered", "1767225601", ""),
                status("wamid.A", "read", "1767225602", ""),
                status("wamid.B", "failed", "1767225603",
                        ",\"errors\":[{\"code\":131026,\"title\":\"Message undeliverable\"}]"),
                status("wamid.C", "failed", "1767225604",
                        ",\"errors\":[{\"code\":131049,\"title\":\"Not delivered to maintain ecosystem health\"}]"),
                status("wamid.D", "deleted", "1767225605", "")));

        WebhookResponse response = signedPost(body);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.events()).extracting(DeliveryEvent::status).containsExactly(
                DeliveryStatus.SENT, DeliveryStatus.DELIVERED, DeliveryStatus.READ,
                DeliveryStatus.BOUNCED, DeliveryStatus.FAILED_AT_PROVIDER, DeliveryStatus.UNKNOWN);

        DeliveryEvent sent = response.events().getFirst();
        assertThat(sent.providerName()).isEqualTo("meta");
        assertThat(sent.providerMessageId()).isEqualTo("wamid.A");
        assertThat(sent.providerEventId()).isEqualTo("wamid.A:sent:1767225600");
        assertThat(sent.timestamp()).isEqualTo(Instant.ofEpochSecond(1767225600));
        assertThat(sent.reason()).isNull();
        assertThat(sent.attributes())
                .containsEntry("status", "sent")
                .containsEntry("phone_number_id", "100000000000001")
                .containsEntry("biz_opaque_callback_data", "req-1")
                .containsEntry("pricing_category", "utility")
                .containsEntry("conversation_category", "utility");

        DeliveryEvent bounced = response.events().get(3);
        assertThat(bounced.reason()).isEqualTo("131026 Message undeliverable");
        assertThat(bounced.attributes()).containsEntry("error_code", "131026")
                .containsEntry("error_title", "Message undeliverable");
        assertThat(response.events().get(4).reason()).startsWith("131049 ");
    }

    @Test
    void recipientNumber_neverLeaksIntoEventsOrLogs() throws Exception {
        String body = statusPayload(status("wamid.B", "failed", "1767225603",
                ",\"errors\":[{\"code\":131026,\"title\":\"Message undeliverable\"}]"));

        WebhookResponse response = signedPost(body);

        assertThat(response.events()).isNotEmpty().allSatisfy(event -> {
            assertThat(event.attributes()).doesNotContainKey("recipient_id");
            assertThat(event.attributes().values()).noneMatch(v -> v.contains(RECIPIENT));
            assertThat(String.valueOf(event.reason())).doesNotContain(RECIPIENT);
            assertThat(event.toString()).doesNotContain(RECIPIENT);
        });
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(line -> line.contains(RECIPIENT));
    }

    @Test
    void inboundMessages_areCountedAndIgnored_withA200() throws Exception {
        String body = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{"
                + "\"metadata\":{\"phone_number_id\":\"100000000000001\"},"
                + "\"contacts\":[{\"wa_id\":\"" + RECIPIENT + "\"}],"
                + "\"messages\":[{\"from\":\"" + RECIPIENT + "\",\"id\":\"wamid.IN\",\"type\":\"text\","
                + "\"text\":{\"body\":\"hello\"}}]}}]}]}";

        WebhookResponse response = signedPost(body);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.events()).isEmpty();
        assertThat(logs.list).anyMatch(e -> e.getFormattedMessage().contains("1 inbound message(s) ignored"));
    }

    @Test
    void nothingMappable_isStill200_butAnUnparseableBodyIs400() throws Exception {
        assertThat(signedPost("{}").status()).isEqualTo(200);
        assertThat(signedPost(statusPayload("{\"status\":\"sent\"},{\"id\":\"wamid.X\"}")).events()).isEmpty();
        assertThat(signedPost("[1,2]").status()).isEqualTo(400);
        assertThat(signedPost("{not json").status()).isEqualTo(400);
    }

    @Test
    void missingOrBadTimestamp_usesTheClock() throws Exception {
        WebhookResponse response = signedPost(statusPayload(
                "{\"id\":\"wamid.T\",\"status\":\"delivered\",\"timestamp\":\"soon\"}"));

        assertThat(response.events().getFirst().timestamp()).isEqualTo(CLOCK.instant());
        assertThat(response.events().getFirst().providerEventId()).isEqualTo("wamid.T:delivered:soon");
    }

    @Test
    void routing_onlyTheRootPath_andGetOrPost() {
        assertThat(handler.handle(new WebhookRequest("POST", "status", null, null, null, null)).status())
                .isEqualTo(404);
        assertThat(handler.handle(new WebhookRequest("PUT", "", null, null, null, null)).status()).isEqualTo(405);
    }

    @Test
    void statusMapping_table() {
        assertThat(MetaWebhookHandler.mapStatus("SENT", null)).isEqualTo(DeliveryStatus.SENT);
        assertThat(MetaWebhookHandler.mapStatus("failed", null)).isEqualTo(DeliveryStatus.FAILED_AT_PROVIDER);
        assertThat(MetaWebhookHandler.mapStatus("failed", 131026)).isEqualTo(DeliveryStatus.BOUNCED);
        assertThat(MetaWebhookHandler.mapStatus("played", null)).isEqualTo(DeliveryStatus.UNKNOWN);
        assertThat(MetaWebhookHandler.mapStatus(null, null)).isEqualTo(DeliveryStatus.UNKNOWN);
    }
}
