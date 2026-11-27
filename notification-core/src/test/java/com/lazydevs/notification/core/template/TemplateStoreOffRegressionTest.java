package com.lazydevs.notification.core.template;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.TemplateRenderException;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.template.store.InMemoryTemplateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * DD-38 regression: without a template store the engine renders exactly what 1.2 rendered,
 * through the pre-1.3 constructor and through the {@code TemplateSource} one, and an empty
 * store changes nothing either. Each case has its expected output written out, so a change in
 * how files are found, read, decoded or included shows up here.
 */
class TemplateStoreOffRegressionTest {

    @TempDir
    Path dir;

    private final NotificationProperties properties = new NotificationProperties();

    @BeforeEach
    void setUp() throws IOException {
        properties.getTemplate().setBasePath(dir.toUri().toString());
        write("default/email/HTML.ftl", """
                <#assign who = name>
                [SUBJECT]
                Hi ${who}
                [/SUBJECT]
                [BODY]
                <p>${who} &amp; co</p>
                [/BODY]
                """);
        write("default/email/MULTI.ftl", "[SUBJECT]S[/SUBJECT][BODY]<b>${name}</b>[/BODY][TEXT]${name}[/TEXT]");
        write("acme/email/HTML.ftl", "[SUBJECT]Acme[/SUBJECT]\nplain ${name}\n");
        write("default/sms/INC.ftl", "<#include \"parts/p.ftl\"><#import \"/shared/lib.ftl\" as lib>${lib.up(name)}");
        write("default/sms/parts/p.ftl", "[${name}]");
        write("shared/lib.ftl", "<#function up s><#return s?upper_case></#function>");
        write("default/sms/CRLF.ftl", "one\r\ntwo\r\n\r\n");
        write("templates/default/sms/LEGACY.ftl", "legacy ${name}");
        write("default/sms/LATIN.ftl", "<#include \"latin1.ftl\">");
        Path latin = dir.resolve("default/sms/latin1.ftl");
        Files.write(latin, "<#ftl encoding=\"ISO-8859-1\">café".getBytes(StandardCharsets.ISO_8859_1));
        write("default/sms/SYNTAX.ftl", "${");
        write("default/sms/MODEL.ftl", "${user.name}");
    }

    private void write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("html email, auto-escape off", "acme2", Channel.EMAIL, "HTML", false,
                        "Hi Ada|null|<p>Ada &amp; co</p>"),
                Arguments.of("html email, auto-escape on", "acme2", Channel.EMAIL, "HTML", true,
                        "Hi Ada|null|<p>Ada &amp; co</p>"),
                Arguments.of("tenant file wins", "acme", Channel.EMAIL, "HTML", false, "Acme|plain Ada|null"),
                Arguments.of("explicit text part", "acme", Channel.EMAIL, "MULTI", true, "S|Ada|<b>Ada</b>"),
                Arguments.of("relative include and absolute import", "acme", Channel.SMS, "INC", false,
                        "null|[Ada]ADA|null"),
                Arguments.of("CRLF file", "acme", Channel.SMS, "CRLF", false, "null|one\ntwo|null"),
                Arguments.of("pre-1.1.2 location", "acme", Channel.SMS, "LEGACY", false, "null|legacy Ada|null"),
                Arguments.of("include with an encoding header", "acme", Channel.SMS, "LATIN", false,
                        "null|café|null"));
    }

    private NotificationTemplateEngine preStoreConstructor() {
        return new NotificationTemplateEngine(properties, new DefaultResourceLoader());
    }

    private NotificationTemplateEngine sourceWithoutStore() {
        return new NotificationTemplateEngine(properties,
                new ResourceLoaderTemplateSource(new DefaultResourceLoader(), properties), null);
    }

    private NotificationTemplateEngine emptyStore() {
        properties.getTemplate().setLegacyHelpers(false);
        return new NotificationTemplateEngine(properties,
                new ResourceLoaderTemplateSource(new DefaultResourceLoader(), properties),
                new InMemoryTemplateStore(1024));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void rendersTheSameWithoutAStore(String name, String tenant, Channel channel, String templateId,
                                     boolean autoEscape, String expected) {
        properties.getTemplate().setAutoEscape(autoEscape);
        for (Function<TemplateStoreOffRegressionTest, NotificationTemplateEngine> factory :
                java.util.List.<Function<TemplateStoreOffRegressionTest, NotificationTemplateEngine>>of(
                        TemplateStoreOffRegressionTest::preStoreConstructor,
                        TemplateStoreOffRegressionTest::sourceWithoutStore,
                        TemplateStoreOffRegressionTest::emptyStore)) {
            RenderedContent content = factory.apply(this).render(request(tenant, channel, templateId));
            assertThat(content.subject() + "|" + content.textBody() + "|" + content.htmlBody())
                    .as(name).isEqualTo(expected);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void failsTheSameWithoutAStore(String templateId, String expectedCode, String expectedPath) {
        for (NotificationTemplateEngine engine : java.util.List.of(preStoreConstructor(), sourceWithoutStore(),
                emptyStore())) {
            TemplateRenderException e = catchThrowableOfType(TemplateRenderException.class,
                    () -> engine.render(request("acme", Channel.SMS, templateId)));
            assertThat(e.getErrorCode()).isEqualTo(expectedCode);
            assertThat(e.getVariablePath()).isEqualTo(expectedPath);
            assertThat(e.getMessage()).startsWith("Template " + templateId + " for tenant acme");
        }
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of("SYNTAX", "TEMPLATE_INVALID", null),
                Arguments.of("MODEL", "TEMPLATE_MODEL_INVALID", "user.name"));
    }

    private static NotificationRequest request(String tenant, Channel channel, String templateId) {
        return NotificationRequest.builder()
                .tenantId(tenant)
                .channel(channel)
                .templateId(templateId)
                .templateData(Map.of("name", "Ada", "user", Map.of()))
                .build();
    }
}
