package com.lazydevs.notification.channel.email.smtp;

import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;

/**
 * Hands a finished message to the mail transport.
 * The default is {@link Transport#send(jakarta.mail.Message)}; a test passes its own
 * implementation to {@link SmtpEmailProvider#withSender(SmtpSender)} to capture the
 * message or simulate a server reply.
 *
 * @since 1.1.1
 */
@FunctionalInterface
public interface SmtpSender {

    /**
     * Send the message.
     *
     * @param message the complete message, recipients included
     * @throws MessagingException when the transport rejects the message
     */
    void send(MimeMessage message) throws MessagingException;
}
