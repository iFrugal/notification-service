package com.lazydevs.notification.core.template;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.exception.TemplateRenderException;
import com.lazydevs.notification.api.exception.TemplateRenderException.Reason;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.core.config.NotificationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * DD-26: {@code <#include>} and {@code <#import>} resolve against
 * {@code notification.template.base-path}, relative to the including template, and cannot
 * leave the base path.
 */
class NotificationTemplateEngineIncludeTest {

    @TempDir
    Path dir;

    private Path base;
    private final NotificationProperties properties = new NotificationProperties();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(BasePathTemplateLoader.class);

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createDirectories(dir.resolve("base"));
        properties.getTemplate().setBasePath(base.toUri().toString());
        // Where a traversal out of the base path would land
        write(dir.resolve("outside.ftl"), "SENTINEL");
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private void write(String relativePath, String content) throws IOException {
        write(base.resolve(relativePath), content);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private NotificationTemplateEngine engine() {
        return new NotificationTemplateEngine(properties, new DefaultResourceLoader());
    }

    private static NotificationRequest request(String tenantId, String templateId) {
        return NotificationRequest.builder()
                .requestId("r-1")
                .tenantId(tenantId)
                .templateId(templateId)
                .channel(Channel.SMS)
                .templateData(new HashMap<>(Map.of("name", "Ada")))
                .build();
    }

    private String render(NotificationTemplateEngine engine, String tenantId) {
        return engine.render(request(tenantId, "T")).textBody();
    }

    @Test
    void relativeInclude_resolvesNextToTheIncludingTemplate() throws IOException {
        write("default/sms/T.ftl", "<#include 'parts/hello.ftl'>");
        write("default/sms/parts/hello.ftl", "Hello ${name}");

        assertThat(render(engine(), "acme")).isEqualTo("Hello Ada");
    }

    @Test
    void absoluteIncludeAndImport_resolveFromTheBasePath() throws IOException {
        write("default/sms/T.ftl", "<#include '/shared/sig.ftl'> <#import '/shared/lib.ftl' as lib>${lib.shout(name)}");
        write("shared/sig.ftl", "-- team");
        write("shared/lib.ftl", "<#function shout s><#return s?upper_case></#function>");

        assertThat(render(engine(), "acme")).isEqualTo("-- team ADA");
    }

    @Test
    void tenantTemplate_includesRelativeToItsOwnDirectory() throws IOException {
        write("acme/sms/T.ftl", "<#include 'footer.ftl'> / <#include '../../default/sms/footer.ftl'>");
        write("acme/sms/footer.ftl", "acme footer");
        write("default/sms/footer.ftl", "default footer");

        assertThat(render(engine(), "acme")).isEqualTo("acme footer / default footer");
    }

    @Test
    void nestedIncludes_resolveRelativeToEachIncludingTemplate() throws IOException {
        write("default/sms/T.ftl", "<#include 'a/one.ftl'>");
        write("default/sms/a/one.ftl", "one <#include 'b/two.ftl'>");
        write("default/sms/a/b/two.ftl", "two");

        assertThat(render(engine(), "acme")).isEqualTo("one two");
    }

    @Test
    void classpathBasePath_resolvesIncludesAndImports() {
        properties.getTemplate().setBasePath("classpath:/templates/");

        assertThat(engine().render(request("acme", "INCLUDES")).textBody()).isEqualTo("Hello Ada ADA!");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../../outside.ftl", "/../outside.ftl", "../../../../outside.ftl"})
    void includeBackingOutOfTheBasePath_isRefused(String include) throws IOException {
        write("default/sms/T.ftl", "<#include '" + include + "'>");

        TemplateRenderException e = catchThrowableOfType(TemplateRenderException.class,
                () -> render(engine(), "acme"));

        assertThat(e.getReason()).isEqualTo(Reason.INVALID_TEMPLATE);
        assertThat(e.getMessage()).doesNotContain("SENTINEL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/hosts", "classpath:/templates/shared/lib.ftl", "/shared/.hidden.ftl",
            "/shared/x%2F..%2F..%2Foutside.ftl"})
    void includeWithAnUnsafeSegment_isRejectedWithAMaskedWarning(String include) throws IOException {
        write("default/sms/T.ftl", "<#include '" + include + "'>");
        write("shared/.hidden.ftl", "HIDDEN");

        TemplateRenderException e = catchThrowableOfType(TemplateRenderException.class,
                () -> render(engine(), "acme"));

        assertThat(e.getReason()).isEqualTo(Reason.INVALID_TEMPLATE);
        assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.startsWith("Rejected template include"))
                .noneMatch(message -> message.contains("hidden") || message.contains("outside"));
    }

    @Test
    void backslashInAnIncludeName_isRefused() throws IOException {
        write("default/sms/T.ftl", "<#include '..\\\\..\\\\..\\\\outside.ftl'>");

        TemplateRenderException e = catchThrowableOfType(TemplateRenderException.class,
                () -> render(engine(), "acme"));

        assertThat(e.getReason()).isEqualTo(Reason.INVALID_TEMPLATE);
    }

    @Test
    void cacheDisabled_rereadsIncludesOnEveryRender() throws IOException {
        properties.getTemplate().setCacheEnabled(false);
        write("default/sms/T.ftl", "<#include 'part.ftl'>");
        write("default/sms/part.ftl", "v1");
        NotificationTemplateEngine engine = engine();
        assertThat(render(engine, "acme")).isEqualTo("v1");

        write("default/sms/part.ftl", "v2");

        assertThat(render(engine, "acme")).isEqualTo("v2");
    }

    @Test
    void cacheEnabled_keepsIncludesUntilTheCacheIsCleared() throws IOException {
        write("default/sms/T.ftl", "<#include 'part.ftl'>");
        write("default/sms/part.ftl", "v1");
        NotificationTemplateEngine engine = engine();
        assertThat(render(engine, "acme")).isEqualTo("v1");

        write("default/sms/part.ftl", "v2");
        assertThat(render(engine, "acme")).isEqualTo("v1");

        engine.clearCache("acme");
        assertThat(render(engine, "acme")).isEqualTo("v2");

        write("default/sms/part.ftl", "v3");
        engine.clearAllCache();
        assertThat(render(engine, "acme")).isEqualTo("v3");
    }

    @Test
    void cacheWithoutTtlOrSizeLimit_stillRenders() throws IOException {
        properties.getTemplate().setCacheTtlSeconds(0);
        properties.getTemplate().setCacheMaxSize(0);
        write("default/sms/T.ftl", "<#include 'part.ftl'>");
        write("default/sms/part.ftl", "v1");

        assertThat(render(engine(), "acme")).isEqualTo("v1");
    }

    @Test
    void loader_readsWithTheRequestedEncodingAndReportsUnknownModificationTimes() throws IOException {
        write("x/y.ftl", "café");
        BasePathTemplateLoader loader = new BasePathTemplateLoader(
                new DefaultResourceLoader(), base.toUri().toString(), NotificationTemplateEngine::isSafeSegment);

        Object source = loader.findTemplateSource("x/y.ftl");
        try (Reader reader = loader.getReader(source, StandardCharsets.UTF_8.name())) {
            char[] buffer = new char[16];
            assertThat(new String(buffer, 0, reader.read(buffer))).isEqualTo("café");
        }
        assertThat(loader.getLastModified(source)).isPositive();
        assertThat(loader.findTemplateSource("x/missing.ftl")).isNull();
        assertThat(loader.findTemplateSource("")).isNull();
        assertThat(loader.findTemplateSource(null)).isNull();
        loader.closeTemplateSource(source);

        Resource unreadable = new DefaultResourceLoader().getResource("classpath:/no/such/resource.ftl");
        assertThat(ResourceLoaderTemplateSource.lastModified(unreadable)).isEqualTo(-1);
    }
}
