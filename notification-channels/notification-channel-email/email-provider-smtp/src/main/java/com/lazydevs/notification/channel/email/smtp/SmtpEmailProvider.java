package com.lazydevs.notification.channel.email.smtp;

import com.lazydevs.notification.api.channel.EmailProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.FailureTypes;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.util.PiiMasking;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/**
 * SMTP email provider implementation.
 * Supports Gmail, generic SMTP servers, etc.
 *
 * <p>{@link #withSender(SmtpSender)} builds an instance that hands the finished
 * message to your own {@link SmtpSender} instead of {@link Transport#send}, for
 * testing an integration without an SMTP server.
 */
@Slf4j
public class SmtpEmailProvider implements EmailProvider {

    private static final String CHARSET_UTF8 = "UTF-8";
    private static final String CT_HTML_UTF8 = "text/html; charset=UTF-8";

    private String host;
    private int port = 587;
    private String username;
    private String password;
    private String fromAddress;
    private String fromName;
    private boolean startTls = true;
    private boolean auth = true;
    private int connectionTimeout = 10000;
    private int timeout = 10000;

    private Session session;
    private SmtpSender sender = Transport::send;
    private boolean senderInjected;

    /**
     * Reflective / bean construction; messages go out through {@link Transport#send}.
     */
    public SmtpEmailProvider() {
        // Settings arrive through configure(...).
    }

    /**
     * A provider that hands every message to {@code sender}.
     * Call {@link #configure(Map)} for the sender address as usual; {@code host} is
     * optional for such an instance, and {@link #init()} only builds the mail session.
     *
     * @param sender receives each finished message
     * @return the provider
     * @since 1.1.1
     */
    public static SmtpEmailProvider withSender(SmtpSender sender) {
        SmtpEmailProvider provider = new SmtpEmailProvider();
        provider.sender = Objects.requireNonNull(sender, "sender");
        provider.senderInjected = true;
        return provider;
    }

    @Override
    public String getProviderName() {
        return "smtp";
    }

    @Override
    public void configure(Map<String, Object> properties) {
        this.host = getString(properties, "host", null);
        this.port = getInt(properties, "port", 587);
        this.username = getString(properties, "username", null);
        this.password = getString(properties, "password", null);
        this.fromAddress = getString(properties, "from-address", getString(properties, "fromAddress", null));
        this.fromName = getString(properties, "from-name", getString(properties, "fromName", null));
        this.startTls = getBoolean(properties, "start-tls", getBoolean(properties, "startTls", true));
        this.auth = getBoolean(properties, "auth", true);
        this.connectionTimeout = getInt(properties, "connection-timeout", 10000);
        this.timeout = getInt(properties, "timeout", 10000);

        log.debug("SMTP provider configured: host={}, port={}, from={}", host, port,
                PiiMasking.maskEmail(fromAddress));
    }

    @Override
    public void init() {
        boolean hasHost = host != null && !host.isBlank();
        if (!hasHost && !senderInjected) {
            throw new IllegalStateException("SMTP host is required");
        }

        Properties props = new Properties();
        if (hasHost) {
            props.put("mail.smtp.host", host);
        }
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", String.valueOf(auth));
        props.put("mail.smtp.starttls.enable", String.valueOf(startTls));
        props.put("mail.smtp.connectiontimeout", String.valueOf(connectionTimeout));
        props.put("mail.smtp.timeout", String.valueOf(timeout));

        if (auth && username != null && password != null) {
            session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            });
        } else {
            session = Session.getInstance(props);
        }

        log.info("SMTP email provider initialized: host={}, port={}", host, port);
    }

    @Override
    public void destroy() {
        // Nothing to clean up
        log.debug("SMTP email provider destroyed");
    }

    @Override
    public SendResult send(NotificationRequest request, RenderedContent content) {
        EmailRecipient recipient = (EmailRecipient) request.getRecipient();

        try {
            MimeMessage message = new MimeMessage(session);

            // From
            String from = fromAddress;
            if (from == null || from.isBlank()) {
                throw new IllegalStateException("From address not configured");
            }
            if (fromName != null && !fromName.isBlank()) {
                message.setFrom(new InternetAddress(from, fromName));
            } else {
                message.setFrom(new InternetAddress(from));
            }

            // To
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient.to()));

            // CC
            if (recipient.cc() != null && !recipient.cc().isEmpty()) {
                for (String cc : recipient.cc()) {
                    message.addRecipient(Message.RecipientType.CC, new InternetAddress(cc));
                }
            }

            // BCC
            if (recipient.bcc() != null && !recipient.bcc().isEmpty()) {
                for (String bcc : recipient.bcc()) {
                    message.addRecipient(Message.RecipientType.BCC, new InternetAddress(bcc));
                }
            }

            // Reply-To
            if (recipient.replyTo() != null && !recipient.replyTo().isBlank()) {
                message.setReplyTo(new Address[]{new InternetAddress(recipient.replyTo())});
            }

            // Subject
            String subject = content.subject();
            if (subject == null || subject.isBlank()) {
                subject = recipient.subject();
            }
            if (subject != null) {
                message.setSubject(subject, CHARSET_UTF8);
            }

            // Body
            if (content.hasHtml() && content.hasText()) {
                // Multipart: text + HTML
                MimeMultipart multipart = new MimeMultipart("alternative");

                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText(content.textBody(), CHARSET_UTF8);
                multipart.addBodyPart(textPart);

                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setContent(content.htmlBody(), CT_HTML_UTF8);
                multipart.addBodyPart(htmlPart);

                message.setContent(multipart);
            } else if (content.hasHtml()) {
                message.setContent(content.htmlBody(), CT_HTML_UTF8);
            } else {
                message.setText(content.textBody(), CHARSET_UTF8);
            }

            // Handle attachments
            if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
                MimeMultipart mixedMultipart = new MimeMultipart("mixed");

                // Add body
                MimeBodyPart bodyPart = new MimeBodyPart();
                if (content.hasHtml()) {
                    bodyPart.setContent(content.htmlBody(), CT_HTML_UTF8);
                } else {
                    bodyPart.setText(content.textBody(), CHARSET_UTF8);
                }
                mixedMultipart.addBodyPart(bodyPart);

                // Add attachments
                for (NotificationRequest.Attachment attachment : request.getAttachments()) {
                    MimeBodyPart attachmentPart = new MimeBodyPart();
                    attachmentPart.setFileName(attachment.filename());
                    attachmentPart.setContent(attachment.content(), attachment.contentType());
                    mixedMultipart.addBodyPart(attachmentPart);
                }

                message.setContent(mixedMultipart);
            }

            // Send
            sender.send(message);

            String messageId = message.getMessageID();
            if (messageId == null) {
                messageId = UUID.randomUUID().toString();
            }

            log.debug("Email sent via SMTP: to={}, messageId={}",
                    PiiMasking.maskEmail(recipient.to()), messageId);

            return SendResult.success(messageId);

        } catch (Exception e) {
            String error = PiiMasking.redact(e.getMessage());
            log.error("Failed to send email via SMTP: to={}, error={}",
                    PiiMasking.maskEmail(recipient.to()), error);
            return SendResult.failure(
                    e.getClass().getSimpleName(), error, classifySmtp(e));
        }
    }

    /**
     * Map a Jakarta Mail exception to a {@link FailureType} for retry
     * decisions (DD-13).
     *
     * <p>Permanent (no retry):
     * <ul>
     *   <li>{@link AuthenticationFailedException} — wrong creds; will
     *       fail every retry until config changes.</li>
     *   <li>{@link SendFailedException} that has invalid addresses
     *       (rejected by the server) but no valid ones — bad input.</li>
     *   <li>{@link AddressException} — caller-supplied address didn't
     *       parse.</li>
     * </ul>
     *
     * <p>Ambiguous (not retried by default): a
     * {@link SocketTimeoutException} anywhere in the cause chain that is
     * not a connect timeout. The server may have accepted the message
     * before its reply was lost, so a resend could deliver it twice.
     *
     * <p>Transient (retry-worthy):
     * <ul>
     *   <li>Other I/O errors anywhere in the cause chain (connect timeout,
     *       connection refused, broken pipe). Detected via
     *       {@link FailureTypes#fromException}.</li>
     *   <li>Generic {@link MessagingException} that isn't one of the
     *       PERMANENT subclasses — could be a temporary 4xx/5xx SMTP
     *       reply from the server.</li>
     * </ul>
     *
     * <p>{@link FailureType#UNKNOWN} for anything we can't reason
     * about; the default {@code RetryPredicate} will retry it
     * (best-effort).
     */
    static FailureType classifySmtp(Throwable t) {
        if (t instanceof AuthenticationFailedException) {
            return FailureType.PERMANENT;
        }
        if (t instanceof SendFailedException sfe
                && sfe.getValidSentAddresses() == null
                && sfe.getInvalidAddresses() != null
                && sfe.getInvalidAddresses().length > 0) {
            return FailureType.PERMANENT;
        }
        if (t instanceof AddressException) {
            return FailureType.PERMANENT;
        }
        if (readTimedOut(t)) {
            return FailureType.AMBIGUOUS;
        }
        // I/O signal in the cause chain → transient.
        FailureType ioGuess = FailureTypes.fromException(t);
        if (ioGuess == FailureType.TRANSIENT) {
            return FailureType.TRANSIENT;
        }
        // Generic MessagingException: most server-side rejections fall
        // here. Treat as transient — Jakarta Mail wraps both 4xx and 5xx
        // SMTP replies indistinguishably, and erring on retry is cheaper
        // than under-retrying real outages. Operators who want stricter
        // can plug a custom RetryPredicate.
        if (t instanceof MessagingException) {
            return FailureType.TRANSIENT;
        }
        return FailureType.UNKNOWN;
    }

    /**
     * A socket timeout after the connection was made. A connect failure in
     * the chain, including a connect timeout, means nothing was sent: either
     * {@link FailureTypes#fromExceptionAfterSubmit} says TRANSIENT, or the
     * mail implementation reports a {@code MailConnectException} (matched by
     * name, as both the {@code com.sun.mail} and the Angus implementations
     * define one).
     */
    private static boolean readTimedOut(Throwable t) {
        if (FailureTypes.fromExceptionAfterSubmit(t) != FailureType.AMBIGUOUS) {
            return false;
        }
        boolean timedOut = false;
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if ("MailConnectException".equals(cur.getClass().getSimpleName())) {
                return false;
            }
            timedOut |= cur instanceof SocketTimeoutException;
        }
        return timedOut;
    }

    @Override
    public boolean isHealthy() {
        if (senderInjected) {
            // The supplied sender owns the transport; there is no server to probe.
            return session != null;
        }
        try {
            Transport transport = session.getTransport("smtp");
            transport.connect();
            transport.close();
            return true;
        } catch (Exception e) {
            log.warn("SMTP health check failed: {}", PiiMasking.redact(e.getMessage()));
            return false;
        }
    }

    // ========== Helper Methods ==========

    private String getString(Map<String, Object> props, String key, String defaultValue) {
        Object value = props.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }

    private int getInt(Map<String, Object> props, String key, int defaultValue) {
        Object value = props.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(value));
    }

    private boolean getBoolean(Map<String, Object> props, String key, boolean defaultValue) {
        Object value = props.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
