package com.lazydevs.notification.channel.email.acs;

import com.azure.communication.email.models.EmailAddress;
import com.azure.communication.email.models.EmailAttachment;
import com.azure.communication.email.models.EmailMessage;
import com.azure.core.util.BinaryData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AcsEmailProvider#operationIdFor(String, String, EmailMessage)}: the same
 * logical message always maps to the same ACS operation id, anything else does not.
 */
class AcsOperationIdTest {

    private static EmailMessage message(String to) {
        return new EmailMessage()
                .setSenderAddress("DoNotReply@example.com")
                .setSubject("Welcome")
                .setToRecipients(List.of(new EmailAddress(to)))
                .setBodyPlainText("Hi")
                .setBodyHtml("<p>Hi</p>");
    }

    private static UUID id(EmailMessage message) {
        return AcsEmailProvider.operationIdFor("acme", "req-1", message);
    }

    @Test
    void sameMessageTwice_sameId() {
        assertThat(id(message("a@example.com"))).isEqualTo(id(message("a@example.com")));
    }

    @Test
    void derivationIsStable_acrossReleasesAndJvms() {
        // Pinned (cross-checked with an independent implementation of the documented
        // encoding): changing the derivation would break retry reuse across a rolling upgrade.
        assertThat(id(message("a@example.com"))).hasToString("3a66ec3e-97fb-34a3-aa92-eb740801cb75");
        assertThat(id(message("a@example.com")).version()).isEqualTo(3);
    }

    @Test
    void batchItemsWithTheSameRequestId_butDifferentRecipients_differ() {
        assertThat(id(message("a@example.com"))).isNotEqualTo(id(message("b@example.com")));
    }

    @Test
    void differentTenants_differ() {
        EmailMessage message = message("a@example.com");
        assertThat(AcsEmailProvider.operationIdFor("acme", "req-1", message))
                .isNotEqualTo(AcsEmailProvider.operationIdFor("globex", "req-1", message));
    }

    @Test
    void differentRequestIds_differ() {
        EmailMessage message = message("a@example.com");
        assertThat(AcsEmailProvider.operationIdFor("acme", "req-1", message))
                .isNotEqualTo(AcsEmailProvider.operationIdFor("acme", "req-2", message));
    }

    @Test
    void nullAndEmptyTenant_andFieldBoundaries_doNotCollide() {
        EmailMessage message = message("a@example.com");
        assertThat(AcsEmailProvider.operationIdFor(null, "req-1", message))
                .isNotEqualTo(AcsEmailProvider.operationIdFor("", "req-1", message));
        // "ab" + "c" must not equal "a" + "bc".
        assertThat(AcsEmailProvider.operationIdFor("ab", "c", message))
                .isNotEqualTo(AcsEmailProvider.operationIdFor("a", "bc", message));
    }

    @Test
    void everyFingerprintField_changesTheId() {
        UUID base = id(message("a@example.com"));
        List<UnaryOperator<EmailMessage>> changes = List.of(
                m -> m.setCcRecipients(List.of(new EmailAddress("c@example.com"))),
                m -> m.setBccRecipients(List.of(new EmailAddress("c@example.com"))),
                m -> m.setSubject("Welcome!"),
                m -> m.setBodyPlainText("Hi there"),
                m -> m.setBodyHtml("<p>Hi there</p>"),
                m -> m.setAttachments(List.of(new EmailAttachment("a.pdf", "application/pdf",
                        BinaryData.fromBytes(new byte[]{1})))));

        for (UnaryOperator<EmailMessage> change : changes) {
            assertThat(id(change.apply(message("a@example.com")))).isNotEqualTo(base);
        }
    }

    @Test
    void ccAndBccWithTheSameAddress_differ() {
        EmailMessage cc = message("a@example.com").setCcRecipients(List.of(new EmailAddress("c@example.com")));
        EmailMessage bcc = message("a@example.com").setBccRecipients(List.of(new EmailAddress("c@example.com")));
        assertThat(id(cc)).isNotEqualTo(id(bcc));
    }

    @Test
    void attachmentBytesAndSender_doNotChangeTheId() {
        // Only attachment names are fingerprinted; the sender is scoped by the tenant.
        EmailMessage one = message("a@example.com").setAttachments(List.of(
                new EmailAttachment("a.pdf", "application/pdf", BinaryData.fromBytes(new byte[]{1}))));
        EmailMessage two = message("a@example.com").setSenderAddress("other@example.com").setAttachments(List.of(
                new EmailAttachment("a.pdf", "application/pdf", BinaryData.fromBytes(new byte[]{2}))));
        assertThat(id(one)).isEqualTo(id(two));
    }
}
