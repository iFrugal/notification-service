package com.lazydevs.notification.channel.email.smtp;

import com.lazydevs.notification.api.channel.EmailProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * SMTP email provider implementation.
 * Supports Gmail, generic SMTP servers, etc.
 */
@Slf4j
public class SmtpEmailProvider implements EmailProvider {

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

        log.debug("SMTP provider configured: host={}, port={}, from={}", host, port, fromAddress);
    }

    @Override
    public void init() {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("SMTP host is required");
        }

        Properties props = new Properties();
        props.put("mail.smtp.host", host);
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
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient.getTo()));

            // CC
            if (recipient.getCc() != null && !recipient.getCc().isEmpty()) {
                for (String cc : recipient.getCc()) {
                    message.addRecipient(Message.RecipientType.CC, new InternetAddress(cc));
                }
            }

            // BCC
            if (recipient.getBcc() != null && !recipient.getBcc().isEmpty()) {
                for (String bcc : recipient.getBcc()) {
                    message.addRecipient(Message.RecipientType.BCC, new InternetAddress(bcc));
                }
            }

            // Reply-To
            if (recipient.getReplyTo() != null && !recipient.getReplyTo().isBlank()) {
                message.setReplyTo(new Address[]{new InternetAddress(recipient.getReplyTo())});
            }

            // Subject
            String subject = content.subject();
            if (subject == null || subject.isBlank()) {
                subject = recipient.getSubject();
            }
            if (subject != null) {
                message.setSubject(subject, "UTF-8");
            }

            // Body
            if (content.hasHtml() && content.hasText()) {
                // Multipart: text + HTML
                MimeMultipart multipart = new MimeMultipart("alternative");

                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText(content.textBody(), "UTF-8");
                multipart.addBodyPart(textPart);

                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setContent(content.htmlBody(), "text/html; charset=UTF-8");
                multipart.addBodyPart(htmlPart);

                message.setContent(multipart);
            } else if (content.hasHtml()) {
                message.setContent(content.htmlBody(), "text/html; charset=UTF-8");
            } else {
                message.setText(content.textBody(), "UTF-8");
            }

            // Handle attachments
            if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
                MimeMultipart mixedMultipart = new MimeMultipart("mixed");

                // Add body
                MimeBodyPart bodyPart = new MimeBodyPart();
                if (content.hasHtml()) {
                    bodyPart.setContent(content.htmlBody(), "text/html; charset=UTF-8");
                } else {
                    bodyPart.setText(content.textBody(), "UTF-8");
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
            Transport.send(message);

            String messageId = message.getMessageID();
            if (messageId == null) {
                messageId = UUID.randomUUID().toString();
            }

            log.debug("Email sent via SMTP: to={}, subject={}, messageId={}",
                    recipient.getTo(), subject, messageId);

            return SendResult.success(messageId);

        } catch (Exception e) {
            log.error("Failed to send email via SMTP: to={}, error={}",
                    recipient.getTo(), e.getMessage());
            return SendResult.failure(e);
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            Transport transport = session.getTransport("smtp");
            transport.connect();
            transport.close();
            return true;
        } catch (Exception e) {
            log.warn("SMTP health check failed: {}", e.getMessage());
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
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.parseInt(String.valueOf(value));
    }

    private boolean getBoolean(Map<String, Object> props, String key, boolean defaultValue) {
        Object value = props.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
