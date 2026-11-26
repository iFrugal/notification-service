package com.lazydevs.notification.core.guard;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.guard.RecipientGuard.Decision;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.PushRecipient;
import com.lazydevs.notification.api.model.Recipient;
import com.lazydevs.notification.api.model.SmsRecipient;
import com.lazydevs.notification.api.model.WhatsAppRecipient;
import com.lazydevs.notification.core.config.NotificationProperties.RecipientGuardChannel;
import com.lazydevs.notification.core.config.NotificationProperties.RecipientGuardProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredRecipientGuardTest {

    private static final String REDIRECT = "qa-inbox@example.com";

    // ------------------------------------------------------------- matching

    @Test
    void domainPattern_matchesExactlyThatDomain_caseInsensitive() {
        ConfiguredRecipientGuard guard = guard("email", rule(null, "@Example.com"));

        assertThat(guard.check(email("Jane@EXAMPLE.COM")).changed()).isFalse();
        assertThat(guard.check(email("jane@mail.example.com")).blocked()).isTrue();
        assertThat(guard.check(email("jane@example.com.evil.io")).blocked()).isTrue();
        assertThat(guard.check(email("not-an-address")).blocked()).isTrue();
    }

    @Test
    void exactEmailPattern_isCaseInsensitive() {
        ConfiguredRecipientGuard guard = guard("email", rule(null, "Lead@Example.org"));

        assertThat(guard.check(email(" lead@example.ORG ")).changed()).isFalse();
        assertThat(guard.check(email("other@example.org")).blocked()).isTrue();
    }

    @Test
    void phonePattern_comparesDigitsOnly_forSmsAndWhatsApp() {
        ConfiguredRecipientGuard guard = guard(Map.of(
                "sms", rule(null, "+1 (555) 010-0123"),
                "WhatsApp", rule(null, "15550100123")));

        assertThat(guard.check(sms("+15550100123")).changed()).isFalse();
        assertThat(guard.check(sms("+15550100999")).blocked()).isTrue();
        assertThat(guard.check(whatsApp("+15550100123")).changed()).isFalse();
    }

    // ------------------------------------------------------------- redirect and drop

    @Test
    void redirect_replacesOneForOne_dedupesAcrossLists_toWins_andPrefixesTheSubject() {
        ConfiguredRecipientGuard guard = guard("email", rule(REDIRECT, "@example.com"));
        NotificationRequest request = request(Channel.EMAIL, new EmailRecipient("id-1", "a@customer.io",
                List.of("ok@example.com", "b@customer.io", "OK@example.com"), List.of("c@customer.io", REDIRECT),
                "reply@customer.io", "Hello"));

        Decision decision = guard.check(request);

        EmailRecipient out = (EmailRecipient) decision.recipient();
        assertThat(out.to()).isEqualTo(REDIRECT);
        assertThat(out.cc()).containsExactly("ok@example.com");
        assertThat(out.bcc()).isEmpty();
        assertThat(out.id()).isEqualTo("id-1");
        assertThat(out.replyTo()).isEqualTo("reply@customer.io");
        assertThat(out.subject()).isEqualTo("Hello");
        assertThat(decision.redirectedOriginals()).containsExactly("a@customer.io", "b@customer.io", "c@customer.io");
        assertThat(decision.subjectPrefix())
                .isEqualTo("[redirected: a@customer.io, b@customer.io, c@customer.io] ");
        assertThat(decision.dropped()).as("the duplicate OK@example.com").isEqualTo(1);
        assertThat(decision.changed()).isTrue();
        assertThat(decision.blocked()).isFalse();
    }

    @Test
    void dropMode_removesNotAllowed_andPromotesACcIntoTo() {
        ConfiguredRecipientGuard guard = guard("email", rule(null, "@example.com"));

        Decision decision = guard.check(request(Channel.EMAIL, new EmailRecipient(null, "a@customer.io",
                List.of("x@example.com", "y@example.com"), List.of("z@customer.io"), null, null)));

        EmailRecipient out = (EmailRecipient) decision.recipient();
        assertThat(out.to()).isEqualTo("x@example.com");
        assertThat(out.cc()).containsExactly("y@example.com");
        assertThat(out.bcc()).isEmpty();
        assertThat(decision.dropped()).isEqualTo(2);
        assertThat(decision.redirectedOriginals()).isEmpty();
        assertThat(decision.subjectPrefix()).as("nothing was redirected").isNull();
    }

    @Test
    void dropMode_promotesABccWhenNoCcIsLeft() {
        ConfiguredRecipientGuard guard = guard("email", rule(null, "@example.com"));

        Decision decision = guard.check(request(Channel.EMAIL, new EmailRecipient(null, "a@customer.io",
                Arrays.asList(" ", null), List.of("hidden@example.com"), null, null)));

        assertThat(((EmailRecipient) decision.recipient()).to()).isEqualTo("hidden@example.com");
        assertThat(((EmailRecipient) decision.recipient()).bcc()).isEmpty();
    }

    @Test
    void dropMode_nothingLeft_blocks() {
        ConfiguredRecipientGuard guard = guard("email", rule(null, "@example.com"));

        Decision decision = guard.check(request(Channel.EMAIL,
                new EmailRecipient(null, "a@customer.io", List.of("b@customer.io"), null, null, null)));

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.changed()).isTrue();
        assertThat(decision.dropped()).isEqualTo(2);
    }

    @Test
    void phoneRedirect_andPhoneDrop() {
        ConfiguredRecipientGuard redirecting = guard("sms", rule("+15550100", "+15550100"));
        ConfiguredRecipientGuard dropping = guard("whatsapp", rule(null, "+15550100"));
        WhatsAppRecipient wa = new WhatsAppRecipient("w", "+4915112345678", "tpl", "de", List.of("h"),
                List.of("b"), List.of(Map.of("k", "v")));

        Decision sms = redirecting.check(sms("+4915112345678"));
        Decision blocked = dropping.check(request(Channel.WHATSAPP, wa));

        assertThat(((SmsRecipient) sms.recipient()).phoneNumber()).isEqualTo("+15550100");
        assertThat(sms.redirectedOriginals()).containsExactly("+4915112345678");
        assertThat(sms.subjectPrefix()).isNull();
        assertThat(blocked.blocked()).isTrue();
        Decision waRedirect = guard("whatsapp", rule("+15550100", "+15550100")).check(request(Channel.WHATSAPP, wa));
        WhatsAppRecipient out = (WhatsAppRecipient) waRedirect.recipient();
        assertThat(out.phoneNumber()).isEqualTo("+15550100");
        assertThat(out).usingRecursiveComparison().ignoringFields("phoneNumber").isEqualTo(wa);
    }

    @Test
    void subjectPrefix_canBeCustomisedOrDisabled() {
        RecipientGuardChannel custom = rule(REDIRECT, "@example.com");
        custom.setSubjectPrefix("[test for {originals}] ");
        RecipientGuardChannel disabled = rule(REDIRECT, "@example.com");
        disabled.setSubjectPrefix("");

        assertThat(guard("email", custom).check(email("a@customer.io")).subjectPrefix())
                .isEqualTo("[test for a@customer.io] ");
        assertThat(guard("email", disabled).check(email("a@customer.io")).subjectPrefix()).isNull();
    }

    @Test
    void unguardedChannelsAndPushRecipientsPassUnchanged() {
        ConfiguredRecipientGuard guard = guard(Map.of("email", rule(null, "@example.com"), "push", rule(null)));
        PushRecipient push = new PushRecipient(null, "token", null, null, null, null, null, null, null, null, null);

        assertThat(guard.check(sms("+15550100999")).changed()).isFalse();
        assertThat(guard.check(request(Channel.PUSH, push)).recipient()).isSameAs(push);
        assertThat(guard.check(request(null, push)).changed()).isFalse();
    }

    // ------------------------------------------------------------- startup validation

    @Test
    void redirectTo_mustItselfBeAllowed() {
        assertThatThrownBy(() -> guard("email", rule("qa@elsewhere.io", "@example.com")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("channels.email.redirect-to must itself be allowed");
        assertThatThrownBy(() -> guard("sms", rule("+15550199", "+15550100")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowedWithoutRedirect_isValid_andBlankPatternsAreIgnored() {
        RecipientGuardChannel rule = rule(" ", "@example.com");
        rule.getAllowed().add(" ");

        assertThat(guard("email", rule).check(email("x@example.com")).changed()).isFalse();
    }

    @Test
    void unknownChannelFailsStartup() {
        assertThatThrownBy(() -> guard("fax", rule(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notification.recipient-guard.channels.fax is not a channel");
    }

    // ------------------------------------------------------------- helpers

    private static ConfiguredRecipientGuard guard(String channel, RecipientGuardChannel rule) {
        return guard(Map.of(channel, rule));
    }

    private static ConfiguredRecipientGuard guard(Map<String, RecipientGuardChannel> rules) {
        RecipientGuardProperties properties = new RecipientGuardProperties();
        properties.setEnabled(true);
        properties.getChannels().putAll(rules);
        return new ConfiguredRecipientGuard(properties);
    }

    private static RecipientGuardChannel rule(String redirectTo, String... allowed) {
        RecipientGuardChannel rule = new RecipientGuardChannel();
        rule.setRedirectTo(redirectTo);
        rule.getAllowed().addAll(List.of(allowed));
        return rule;
    }

    private static NotificationRequest email(String to) {
        return request(Channel.EMAIL, new EmailRecipient(null, to, null, null, null, null));
    }

    private static NotificationRequest sms(String number) {
        return request(Channel.SMS, new SmsRecipient(null, number));
    }

    private static NotificationRequest whatsApp(String number) {
        return request(Channel.WHATSAPP, new WhatsAppRecipient(null, number, null, null, null, null, null));
    }

    private static NotificationRequest request(Channel channel, Recipient recipient) {
        return NotificationRequest.builder().requestId("r").tenantId("t").channel(channel).recipient(recipient)
                .build();
    }
}
