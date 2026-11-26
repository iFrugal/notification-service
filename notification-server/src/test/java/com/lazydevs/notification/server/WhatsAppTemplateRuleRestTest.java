package com.lazydevs.notification.server;

import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.idempotency.IdempotencyKey;
import com.lazydevs.notification.api.idempotency.IdempotencyStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The standalone server with WhatsApp on and {@code validate-before-dispatch}: a request
 * whose WhatsApp template parameter breaks a rule is answered over REST with 422 and the
 * 1.2.1 template error body, before anything is claimed, rate limited or audited, and
 * Meta is never called.
 */
class WhatsAppTemplateRuleRestTest {

    private static final String TEMPLATE = "notification.whatsapp.templates.default.order-shipped.";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(NotificationServerApplication.class)
            .withPropertyValues(
                    "notification.kafka.enabled=false",
                    "WHATSAPP_ENABLED=true",
                    "WHATSAPP_META_PHONE_NUMBER_ID=100000000000001",
                    "WHATSAPP_META_ACCESS_TOKEN=unused-token",
                    TEMPLATE + "name=order_shipped",
                    TEMPLATE + "language=en",
                    TEMPLATE + "body[0]=customerName",
                    TEMPLATE + "body[1]=orderNumber",
                    "notification.template.validate-before-dispatch=true",
                    "notification.idempotency.enabled=true",
                    "notification.audit.enabled=true",
                    "notification.audit.async=false",
                    // One token: a second request that consumed one would be a 429
                    "notification.rate-limit.enabled=true",
                    "notification.rate-limit.default-rule.capacity=1",
                    "notification.rate-limit.default-rule.refill-tokens=1",
                    "notification.rate-limit.default-rule.refill-period=PT1H");

    private static ResultActions send(MockMvc mvc, String customerName) throws Exception {
        String body = """
                {"requestId":"rest-wa-1","idempotencyKey":"wa-key-1","notificationType":"order-shipped",\
                "channel":"WHATSAPP","recipient":{"type":"WHATSAPP","phoneNumber":"+15550100001"},\
                "templateData":{"customerName":%s,"orderNumber":"1042"}}""".formatted(customerName);
        return mvc.perform(post("/api/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Tenant-Id", "default")
                .content(body));
    }

    @Test
    void parameterRuleViolation_is422_withNothingClaimedCountedOrAudited() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

            for (int attempt = 0; attempt < 2; attempt++) {
                send(mvc, "\"Jane\\nDoe\"")
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.error").value("TEMPLATE_PARAMETER_INVALID"))
                        .andExpect(jsonPath("$.reason").value("PARAMETER_INVALID"))
                        .andExpect(jsonPath("$.templateId").value("order-shipped"))
                        .andExpect(jsonPath("$.path").value("customerName"));
            }
            send(mvc, "null")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.reason").value("MODEL_INVALID"))
                    .andExpect(jsonPath("$.path").value("customerName"));

            NotificationService service = context.getBean(NotificationService.class);
            assertThat(service.find("default", "rest-wa-1")).isEmpty();
            assertThat(context.getBean(IdempotencyStore.class)
                    .findExisting(new IdempotencyKey("default", null, "wa-key-1"))).isEmpty();
        });
    }
}
