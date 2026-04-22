package com.lazydevs.notification.channel.email.ses;

import com.lazydevs.notification.api.channel.EmailProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.SendResult;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AWS SES email provider implementation.
 */
@Slf4j
public class SesEmailProvider implements EmailProvider {

    private String region;
    private String fromAddress;
    private String fromName;
    private String configurationSetName;

    private SesV2Client sesClient;

    @Override
    public String getProviderName() {
        return "ses";
    }

    @Override
    public void configure(Map<String, Object> properties) {
        this.region = getString(properties, "region", "us-east-1");
        this.fromAddress = getString(properties, "from-address", getString(properties, "fromAddress", null));
        this.fromName = getString(properties, "from-name", getString(properties, "fromName", null));
        this.configurationSetName = getString(properties, "configuration-set", null);

        log.debug("SES provider configured: region={}, from={}", region, fromAddress);
    }

    @Override
    public void init() {
        sesClient = SesV2Client.builder()
                .region(Region.of(region))
                .build();

        log.info("AWS SES email provider initialized: region={}", region);
    }

    @Override
    public void destroy() {
        if (sesClient != null) {
            sesClient.close();
            log.debug("SES client closed");
        }
    }

    @Override
    public SendResult send(NotificationRequest request, RenderedContent content) {
        EmailRecipient recipient = (EmailRecipient) request.getRecipient();

        try {
            // Build destination
            Destination.Builder destinationBuilder = Destination.builder()
                    .toAddresses(recipient.getTo());

            if (recipient.getCc() != null && !recipient.getCc().isEmpty()) {
                destinationBuilder.ccAddresses(recipient.getCc());
            }
            if (recipient.getBcc() != null && !recipient.getBcc().isEmpty()) {
                destinationBuilder.bccAddresses(recipient.getBcc());
            }

            // Build email content
            EmailContent.Builder emailContentBuilder = EmailContent.builder();

            // Subject
            String subject = content.subject();
            if (subject == null || subject.isBlank()) {
                subject = recipient.getSubject();
            }

            // Body
            Body.Builder bodyBuilder = Body.builder();
            if (content.hasHtml()) {
                bodyBuilder.html(Content.builder().data(content.htmlBody()).charset("UTF-8").build());
            }
            if (content.hasText()) {
                bodyBuilder.text(Content.builder().data(content.textBody()).charset("UTF-8").build());
            }

            Message message = Message.builder()
                    .subject(Content.builder().data(subject).charset("UTF-8").build())
                    .body(bodyBuilder.build())
                    .build();

            emailContentBuilder.simple(message);

            // Build request
            SendEmailRequest.Builder sendRequestBuilder = SendEmailRequest.builder()
                    .fromEmailAddress(buildFromAddress())
                    .destination(destinationBuilder.build())
                    .content(emailContentBuilder.build());

            // Reply-to
            if (recipient.getReplyTo() != null && !recipient.getReplyTo().isBlank()) {
                sendRequestBuilder.replyToAddresses(recipient.getReplyTo());
            }

            // Configuration set
            if (configurationSetName != null && !configurationSetName.isBlank()) {
                sendRequestBuilder.configurationSetName(configurationSetName);
            }

            // Send
            SendEmailResponse response = sesClient.sendEmail(sendRequestBuilder.build());

            log.debug("Email sent via SES: to={}, messageId={}", recipient.getTo(), response.messageId());

            return SendResult.success(response.messageId());

        } catch (Exception e) {
            log.error("Failed to send email via SES: to={}, error={}",
                    recipient.getTo(), e.getMessage());
            return SendResult.failure(e);
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            // Simple health check - get account details
            sesClient.getAccount();
            return true;
        } catch (Exception e) {
            log.warn("SES health check failed: {}", e.getMessage());
            return false;
        }
    }

    private String buildFromAddress() {
        if (fromName != null && !fromName.isBlank()) {
            return String.format("%s <%s>", fromName, fromAddress);
        }
        return fromAddress;
    }

    private String getString(Map<String, Object> props, String key, String defaultValue) {
        Object value = props.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }
}
