package com.lazydevs.notification.server;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the templates shipped in {@code templates/default/} through the server's own
 * {@link NotificationTemplateEngine}, configured by the real {@code application.yml}
 * ({@code notification.template.base-path: classpath:/templates/}).
 * Up to 1.1.1 the engine looked under {@code classpath:/templates/templates/} and none of
 * these could be found.
 */
class ShippedTemplatesRenderTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(NotificationServerApplication.class)
            .withPropertyValues(
                    "notification.kafka.enabled=false",
                    "notification.audit.enabled=false");

    private static NotificationRequest request(Channel channel, String type, Map<String, Object> data) {
        return NotificationRequest.builder()
                .requestId("r-1")
                .tenantId("tenant-without-overrides")
                .notificationType(type)
                .channel(channel)
                .templateData(new HashMap<>(data))
                .build();
    }

    @Test
    void orderConfirmationEmail_rendersFromDefaultTemplate() {
        runner.run(context -> {
            NotificationTemplateEngine engine = context.getBean(NotificationTemplateEngine.class);

            RenderedContent content = engine.render(request(Channel.EMAIL, "ORDER_CONFIRMATION", Map.of(
                    "orderId", "A-1001",
                    "customerName", "Ada <Lovelace>",
                    "items", List.of(Map.of("name", "Widget & Co", "qty", 2, "price", 9.5)),
                    "total", 19,
                    "deliveryDate", "2026-10-09",
                    "trackingUrl", "https://track.example/A-1001")));

            assertThat(content.subject()).isEqualTo("Order Confirmation - #A-1001");
            assertThat(content.htmlBody())
                    .startsWith("<!DOCTYPE html>")
                    .contains("Hello Ada &lt;Lovelace&gt;,")
                    .contains("<td>Widget &amp; Co</td>")
                    .contains("<td>$9.50</td>")
                    .contains("Total: $19.00")
                    .contains("<strong>Expected Delivery:</strong> "
                            + DateTimeFormatter.ofPattern("MMMM dd, yyyy").format(LocalDate.of(2026, 10, 9)))
                    .contains("<a href=\"https://track.example/A-1001\">")
                    .endsWith("</html>");
            assertThat(content.textBody()).isNull();
        });
    }

    @Test
    void passwordResetEmail_rendersFromDefaultTemplate() {
        runner.run(context -> {
            RenderedContent content = context.getBean(NotificationTemplateEngine.class).render(
                    request(Channel.EMAIL, "PASSWORD_RESET", Map.of("resetLink", "https://example.com/reset/abc")));

            assertThat(content.subject()).isEqualTo("Password Reset Request");
            assertThat(content.htmlBody())
                    .contains("Hello there,")
                    .contains("href=\"https://example.com/reset/abc\"")
                    .contains("expire in 60 minutes");
        });
    }

    @Test
    void otpSms_rendersFromDefaultTemplate() {
        runner.run(context -> {
            RenderedContent content = context.getBean(NotificationTemplateEngine.class).render(
                    request(Channel.SMS, "OTP", Map.of("otp", "482913")));

            assertThat(content.textBody()).isEqualTo(
                    "Your verification code is: 482913. This code expires in 5 minutes. "
                            + "Do not share this code with anyone.");
        });
    }
}
