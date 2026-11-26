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
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * DD-26: {@code notification.template.legacy-helpers} decides whether templates get the
 * persistence-utils helpers {@code eval}, {@code file}, {@code js} and {@code serde_*} and an
 * unrestricted {@code ?new}; {@code uuid}, {@code trim} and the notification helpers stay.
 */
class NotificationTemplateEngineLegacyHelpersTest {

    private static final String NEW_OBJECT_CONSTRUCTOR =
            "<#assign construct = 'freemarker.template.utility.ObjectConstructor'?new()>"
                    + "${construct('java.lang.StringBuilder', 'built')}";

    @TempDir
    Path dir;

    private final NotificationProperties properties = new NotificationProperties();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(NotificationTemplateEngine.class);

    @BeforeEach
    void setUp() {
        properties.getTemplate().setBasePath(dir.toUri().toString());
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private String render(String source, Map<String, Object> data) throws IOException {
        Path file = dir.resolve("default/sms/T.ftl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return new NotificationTemplateEngine(properties, new DefaultResourceLoader())
                .render(NotificationRequest.builder()
                        .requestId("r-1")
                        .tenantId("acme")
                        .templateId("T")
                        .channel(Channel.SMS)
                        .templateData(new HashMap<>(data))
                        .build())
                .textBody();
    }

    private List<String> legacyWarnings() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("notification.template.legacy-helpers=true"))
                .toList();
    }

    @Test
    void legacyHelpersAreOnByDefault_andLogOneDeprecationWarningPerEngine() {
        assertThat(properties.getTemplate().isLegacyHelpers()).isTrue();

        new NotificationTemplateEngine(properties, new DefaultResourceLoader());

        assertThat(legacyWarnings()).singleElement().asString()
                .contains("eval", "file", "js", "serde_deserializeToMap", "serde_serialize", "false in 2.0");
    }

    @Test
    void legacyOn_evalAndFileRenderThroughPersistenceUtils() throws IOException {
        Path snippet = dir.resolve("snippet.ftl");
        Files.writeString(snippet, "${'from file'}");

        String out = render("${eval(\"${'a' + 'b'}\")} ${file(path)}", Map.of("path", snippet.toString()));

        assertThat(out).isEqualTo("ab from file");
    }

    @Test
    void legacyOn_fileFailureIsAModelError() {
        TemplateRenderException e = catchThrowableOfType(TemplateRenderException.class,
                () -> render("${file('/no/such/file.ftl')}", Map.of()));

        assertThat(e.getReason()).isEqualTo(Reason.MODEL_INVALID);
    }

    @Test
    void legacyOn_serdeHelpersRoundTripJson() throws IOException {
        String out = render("${serde_deserializeToMap('{\"a\": 7}').a} ${serde_serialize(order)}",
                Map.of("order", Map.of("id", "A-1")));

        assertThat(out).isEqualTo("7 {\"id\":\"A-1\"}");
    }

    @Test
    void legacyOn_serdeSerializeOfAScalarSerializesItsWrapper_asPersistenceUtilsDid() throws IOException {
        assertThat(render("${serde_serialize('x')}", Map.of())).isEqualTo("{\"asString\":\"x\"}");
    }

    @Test
    void legacyOn_jsIsPresent_andFailsWithoutAScriptEngine() throws IOException {
        assertThat(render("${js???c}", Map.of())).isEqualTo("true");

        TemplateRenderException e = catchThrowableOfType(TemplateRenderException.class,
                () -> render("${js('noSuchFunction', 1)}", Map.of()));
        assertThat(e.getReason()).isEqualTo(Reason.MODEL_INVALID);
    }

    @Test
    void legacyOn_helperArgumentCountsAreChecked() {
        for (String call : List.of("eval()", "file()", "js()", "serde_deserializeToMap()", "serde_serialize()",
                "trim()")) {
            TemplateRenderException e = catchThrowableOfType(TemplateRenderException.class,
                    () -> render("${" + call + "}", Map.of()));
            assertThat(e.getReason()).as(call).isEqualTo(Reason.MODEL_INVALID);
        }
    }

    @Test
    void legacyOn_newBuiltInIsUnrestricted() throws IOException {
        assertThat(render(NEW_OBJECT_CONSTRUCTOR, Map.of())).isEqualTo("built");
    }

    @Test
    void legacyOff_noWarningAndNoLegacyHelpers() throws IOException {
        properties.getTemplate().setLegacyHelpers(false);

        String out = render("${eval???c} ${file???c} ${js???c} ${serde_deserializeToMap???c} ${serde_serialize???c}",
                Map.of());

        assertThat(out).isEqualTo("false false false false false");
        assertThat(legacyWarnings()).isEmpty();
    }

    @Test
    void legacyOff_callingALegacyHelperIsAModelErrorNamingIt() throws IOException {
        properties.getTemplate().setLegacyHelpers(false);

        TemplateRenderException e = catchThrowableOfType(TemplateRenderException.class,
                () -> render("${file('/etc/hosts')}", Map.of()));

        assertThat(e.getReason()).isEqualTo(Reason.MODEL_INVALID);
        assertThat(e.getVariablePath()).isEqualTo("file");
    }

    @Test
    void legacyOff_keepsUuidTrimAndTheNotificationHelpers() throws IOException {
        properties.getTemplate().setLegacyHelpers(false);

        String out = render("${uuid()?length} [${trim('  a  ')}] ${formatCurrency(5, 'USD')} ${capitalize('ada')}",
                Map.of());

        assertThat(out).isEqualTo("36 [a] $5.00 Ada");
    }

    @Test
    void legacyOff_dataEntriesNamedLikeLegacyHelpersAreData() throws IOException {
        properties.getTemplate().setLegacyHelpers(false);

        assertThat(render("${file} ${eval}", Map.of("file", "report.pdf", "eval", "pending")))
                .isEqualTo("report.pdf pending");
    }

    @Test
    void legacyOff_newBuiltInCannotReachTheDangerousClasses() throws IOException {
        properties.getTemplate().setLegacyHelpers(false);

        TemplateRenderException e = catchThrowableOfType(TemplateRenderException.class,
                () -> render(NEW_OBJECT_CONSTRUCTOR, Map.of()));

        assertThat(e.getReason()).isEqualTo(Reason.MODEL_INVALID);
    }
}
