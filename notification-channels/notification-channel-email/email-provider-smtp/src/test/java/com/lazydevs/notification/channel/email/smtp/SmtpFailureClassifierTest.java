package com.lazydevs.notification.channel.email.smtp;

import com.lazydevs.notification.api.model.FailureType;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the SMTP provider's failure classifier.
 *
 * <p>Calls {@code SmtpEmailProvider.classifySmtp(Throwable)} directly —
 * no real SMTP server needed. The classifier is package-private to make
 * it testable without exposing it on the public {@code EmailProvider}
 * interface.
 */
class SmtpFailureClassifierTest {

    @Test
    void authenticationFailure_isPermanent() {
        // Wrong creds — every retry will fail until config changes.
        Throwable t = new AuthenticationFailedException("bad password");
        assertThat(SmtpEmailProvider.classifySmtp(t)).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void addressException_isPermanent() {
        // Caller supplied a malformed address — bad input.
        Throwable t = new AddressException("malformed", "not-an-email");
        assertThat(SmtpEmailProvider.classifySmtp(t)).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void sendFailedWithOnlyInvalidAddresses_isPermanent() throws Exception {
        // Server rejected every recipient — bad input, no point retrying.
        SendFailedException sfe = new SendFailedException(
                "all recipients invalid",
                new MessagingException("rejected"),
                /* validSent */ null,
                /* validUnsent */ null,
                new InternetAddress[]{ new InternetAddress("bad@invalid") });
        assertThat(SmtpEmailProvider.classifySmtp(sfe)).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void messagingExceptionWithIOCause_isTransient() {
        // I/O signal in cause chain wins over MessagingException's
        // generic-transient mapping — same semantic outcome here, but
        // the test lets us verify the cause-chain walk works.
        MessagingException t = new MessagingException(
                "smtp connect failed", new SocketTimeoutException("timeout"));
        assertThat(SmtpEmailProvider.classifySmtp(t)).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void plainMessagingException_isTransient() {
        // Generic server-side rejection (could be 4xx or 5xx SMTP) —
        // Jakarta Mail wraps both indistinguishably; classifier errs
        // on retry per the doc.
        Throwable t = new MessagingException("421 try again later");
        assertThat(SmtpEmailProvider.classifySmtp(t)).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void rawIOException_isTransient() {
        Throwable t = new IOException("connection reset");
        assertThat(SmtpEmailProvider.classifySmtp(t)).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void unrelatedException_isUnknown() {
        // Something we don't recognise — defer to default predicate.
        Throwable t = new IllegalArgumentException("weird");
        assertThat(SmtpEmailProvider.classifySmtp(t)).isEqualTo(FailureType.UNKNOWN);
    }
}
