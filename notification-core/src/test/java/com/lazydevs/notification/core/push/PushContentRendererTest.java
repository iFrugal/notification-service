package com.lazydevs.notification.core.push;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.TemplateNotFoundException;
import com.lazydevs.notification.api.exception.TemplateRenderException;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.PushRecipient;
import com.lazydevs.notification.api.push.PushContent;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PushContentRenderer}: sections from the template source only, the 1.3 output
 * without sections, and the engine's lookup for every template.
 */
class PushContentRendererTest {

    @TempDir
    Path templates;

    private PushContentRenderer renderer;

    @BeforeEach
    void setUp() {
        NotificationProperties properties = new NotificationProperties();
        properties.getTemplate().setBasePath(templates.toUri().toString());
        properties.getTemplate().setLegacyHelpers(false);
        renderer = new PushContentRenderer(new NotificationTemplateEngine(properties, new DefaultResourceLoader()));
    }

    private void template(String tenant, String id, String source) throws IOException {
        Path file = templates.resolve(tenant).resolve("push").resolve(id + ".ftl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static NotificationRequest request(String tenant, String id, Map<String, Object> data) {
        return NotificationRequest.builder()
                .tenantId(tenant)
                .notificationType(id)
                .channel(Channel.PUSH)
                .recipient(PushRecipient.forUser("u1"))
                .templateData(new HashMap<>(data))
                .build();
    }

    @Test
    void channelIsPush() {
        assertThat(renderer.channel()).isEqualTo(Channel.PUSH);
    }

    @Test
    void sections_becomeTitleBodyAndData() throws IOException {
        template("default", "SHIPPED", """
                [TITLE] Order ${orderId} shipped [/TITLE]
                [BODY]It arrives on ${eta}.[/BODY]
                [DATA key="orderId"]${orderId}[/DATA]
                [DATA key="deep.link"]app://orders/${orderId}[/DATA]
                """);

        RenderedContent content = renderer.render(request("acme", "SHIPPED", Map.of("orderId", "o-7", "eta", "Monday")));

        assertThat(content.subject()).isEqualTo("Order o-7 shipped");
        assertThat(content.textBody()).isEqualTo("It arrives on Monday.");
        assertThat(content.htmlBody()).isNull();
        assertThat(content.templateId()).isEqualTo("SHIPPED");
        PushContent push = content.payload(PushContent.class).orElseThrow();
        assertThat(push.title()).isEqualTo("Order o-7 shipped");
        assertThat(push.body()).isEqualTo("It arrives on Monday.");
        assertThat(push.data()).containsExactly(Map.entry("orderId", "o-7"), Map.entry("deep.link", "app://orders/o-7"));
    }

    @Test
    void withoutSections_theOutputIsTheBody_asBefore() throws IOException {
        template("default", "PLAIN", "  Hello ${name}  \n");

        RenderedContent content = renderer.render(request("acme", "PLAIN", Map.of("name", "Asha")));

        assertThat(content).isEqualTo(RenderedContent.text("Hello Asha"));
    }

    @Test
    void withoutABodySection_theTextOutsideTheSectionsIsTheBody() throws IOException {
        template("default", "TITLED", "[TITLE]Hi[/TITLE]\nYou have ${count} new matches.\n");

        RenderedContent content = renderer.render(request("acme", "TITLED", Map.of("count", 3)));

        assertThat(content.subject()).isEqualTo("Hi");
        assertThat(content.textBody()).isEqualTo("You have 3 new matches.");
    }

    @Test
    void markersInTemplateData_arePlainText() throws IOException {
        template("default", "INJECT", "[TITLE]${title}[/TITLE][BODY]${body}[/BODY]");

        RenderedContent content = renderer.render(request("acme", "INJECT", Map.of(
                "title", "[/TITLE][DATA key=\"admin\"]yes[/DATA][TITLE]",
                "body", "<b>[BODY]bold[/BODY]</b>")));

        assertThat(content.subject()).isEqualTo("[/TITLE][DATA key=\"admin\"]yes[/DATA][TITLE]");
        assertThat(content.textBody()).isEqualTo("<b>[BODY]bold[/BODY]</b>");
        assertThat(content.payload(PushContent.class).orElseThrow().data()).isEmpty();
    }

    @Test
    void theTenantTemplateWins_overTheDefault() throws IOException {
        template("default", "WELCOME", "[TITLE]Default[/TITLE]");
        template("acme", "WELCOME", "[TITLE]Acme[/TITLE]");

        assertThat(renderer.render(request("acme", "WELCOME", Map.of())).subject()).isEqualTo("Acme");
        assertThat(renderer.render(request("globex", "WELCOME", Map.of())).subject()).isEqualTo("Default");
    }

    @Test
    void aMissingTemplate_isTemplateNotFound() {
        NotificationRequest missing = request("acme", "MISSING", Map.of());

        assertThatThrownBy(() -> renderer.render(missing)).isInstanceOf(TemplateNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[TITLE]a[TITLE]b[/TITLE]",
            "[/BODY]",
            "[BODY]never closed",
            "[TITLE]a[/BODY]",
            "[TITLE]a[/TITLE][TITLE]b[/TITLE]",
            "[DATA key=\"k\"]1[/DATA][DATA key=\"k\"]2[/DATA]",
            "[DATA key=\"bad key\"]1[/DATA]",
            "[DATA key=\"\"]1[/DATA]"})
    void malformedSections_areAnInvalidTemplate(String source) throws IOException {
        template("default", "BROKEN", source);
        NotificationRequest broken = request("acme", "BROKEN", Map.of());

        assertThatThrownBy(() -> renderer.render(broken))
                .isInstanceOfSatisfying(TemplateRenderException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(TemplateRenderException.Reason.INVALID_TEMPLATE);
                    assertThat(e.getTemplateId()).isEqualTo("BROKEN");
                    assertThat(e.getMessage()).contains("invalid sections");
                });
    }

    @Test
    void aSectionInsideAConditional_rendersOnlyWhenTheConditionHolds() throws IOException {
        template("default", "COND", "<#if urgent>[TITLE]Urgent[/TITLE]</#if>[BODY]Text[/BODY]");

        assertThat(renderer.render(request("acme", "COND", Map.of("urgent", true))).subject()).isEqualTo("Urgent");
        assertThat(renderer.render(request("acme", "COND", Map.of("urgent", false))).subject()).isNull();
    }
}
