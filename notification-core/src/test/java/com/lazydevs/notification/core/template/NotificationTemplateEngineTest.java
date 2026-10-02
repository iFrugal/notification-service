package com.lazydevs.notification.core.template;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.TemplateNotFoundException;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.core.config.NotificationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTemplateEngineTest {

    private static final AtomicInteger IDS = new AtomicInteger();

    @TempDir
    Path dir;

    private NotificationProperties properties;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
    }

    private NotificationTemplateEngine engine() {
        return new NotificationTemplateEngine(properties, new DefaultResourceLoader());
    }

    private static NotificationRequest request(String tenantId, Channel channel, String type,
                                               Map<String, Object> data) {
        return NotificationRequest.builder()
                .requestId("r-1")
                .tenantId(tenantId)
                .notificationType(type)
                .channel(channel)
                .templateData(new HashMap<>(data))
                .build();
    }

    private Path write(String relativePath, String content) throws IOException {
        Path file = dir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    /**
     * Writes {@code source} as a fresh default email template under the temp base path and renders it.
     */
    private RenderedContent renderEmail(String source, Map<String, Object> data) throws IOException {
        String id = "T" + IDS.incrementAndGet();
        write("default/email/" + id + ".ftl", source);
        properties.getTemplate().setBasePath(dir.toUri().toString());
        return engine().render(request("acme", Channel.EMAIL, id, data));
    }

    /**
     * Renders {@code expression} as the whole body of a text email and returns the body.
     */
    private String eval(String expression, Map<String, Object> data) throws IOException {
        return renderEmail("[SUBJECT]s[/SUBJECT][BODY]" + expression + "[/BODY]", data).textBody();
    }

    private String eval(String expression) throws IOException {
        return eval(expression, Map.of());
    }

    @Nested
    class PathResolution {

        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private final Logger logger = (Logger) LoggerFactory.getLogger(NotificationTemplateEngine.class);

        @BeforeEach
        void attach() {
            appender.start();
            logger.addAppender(appender);
        }

        @AfterEach
        void detach() {
            logger.detachAppender(appender);
        }

        private List<String> warnings() {
            return appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        @Test
        void defaultBasePath_loadsDefaultTemplateFromClasspathTemplatesDirectory() {
            // classpath:/templates/default/email/WELCOME.ftl; 1.1.1 looked under classpath:/templates/templates/
            RenderedContent content = engine().render(
                    request("other", Channel.EMAIL, "WELCOME", Map.of("name", "Ada")));

            assertThat(content.subject()).isEqualTo("Welcome, Ada");
            assertThat(content.htmlBody()).isEqualTo("<p>Hello Ada, welcome aboard.</p>");
            assertThat(warnings()).isEmpty();
        }

        @Test
        void defaultBasePath_tenantTemplateOverridesDefault() {
            RenderedContent content = engine().render(
                    request("acme", Channel.EMAIL, "WELCOME", Map.of("name", "Ada")));

            assertThat(content.subject()).isEqualTo("Acme welcomes Ada");
            assertThat(content.textBody()).isEqualTo("Hello Ada from Acme.");
        }

        @Test
        void basePathWithoutTrailingSlash_resolves() throws IOException {
            write("default/sms/OTP.ftl", "Code ${otp}");
            String uri = dir.toUri().toString();
            properties.getTemplate().setBasePath(uri.substring(0, uri.length() - 1));

            RenderedContent content = engine().render(request("acme", Channel.SMS, "OTP", Map.of("otp", "42")));

            assertThat(content.textBody()).isEqualTo("Code 42");
        }

        @Test
        void legacyDoubledPath_stillLoadsAndWarnsOncePerFile() throws IOException {
            write("templates/default/sms/OTP.ftl", "Legacy ${otp}");
            properties.getTemplate().setBasePath(dir.toUri().toString());
            properties.getTemplate().setCacheEnabled(false);
            NotificationTemplateEngine engine = engine();

            assertThat(engine.render(request("acme", Channel.SMS, "OTP", Map.of("otp", "1"))).textBody())
                    .isEqualTo("Legacy 1");
            assertThat(engine.render(request("acme", Channel.SMS, "OTP", Map.of("otp", "2"))).textBody())
                    .isEqualTo("Legacy 2");

            assertThat(warnings()).singleElement().satisfies(message -> assertThat(message)
                    .contains(dir.toUri() + "templates/default/sms/OTP.ftl")
                    .contains(dir.toUri() + "default/sms/OTP.ftl")
                    .contains("removed in 1.2"));
        }

        @Test
        void tenantTemplateAtLegacyPath_stillOverridesDefaultAtNewPath() throws IOException {
            write("default/sms/OTP.ftl", "Default ${otp}");
            write("templates/acme/sms/OTP.ftl", "Acme legacy ${otp}");
            properties.getTemplate().setBasePath(dir.toUri().toString());

            assertThat(engine().render(request("acme", Channel.SMS, "OTP", Map.of("otp", "7"))).textBody())
                    .isEqualTo("Acme legacy 7");
        }

        @Test
        void newPath_winsOverLegacyPath() throws IOException {
            write("default/sms/OTP.ftl", "New ${otp}");
            write("templates/default/sms/OTP.ftl", "Legacy ${otp}");
            properties.getTemplate().setBasePath(dir.toUri().toString());

            assertThat(engine().render(request("acme", Channel.SMS, "OTP", Map.of("otp", "7"))).textBody())
                    .isEqualTo("New 7");
            assertThat(warnings()).isEmpty();
        }

        @Test
        void missingTemplate_throwsTemplateNotFound() {
            properties.getTemplate().setBasePath(dir.toUri().toString());

            assertThatThrownBy(() -> engine().render(request("acme", Channel.SMS, "NOPE", Map.of())))
                    .isInstanceOf(TemplateNotFoundException.class);
        }
    }

    @Nested
    class MarkerParsing {

        private static final String TEMPLATE =
                "[SUBJECT]Hello ${name}[/SUBJECT]\n[BODY]Dear ${name}, your OTP is ${code}.[/BODY]\n";

        @Test
        void bodyMarkersInData_doNotReplaceTheBody() throws IOException {
            String name = "Bob[BODY]Your account is locked, verify at http://evil.example[/BODY]";

            RenderedContent content = renderEmail(TEMPLATE, Map.of("name", name, "code", "123456"));

            assertThat(content.subject()).isEqualTo("Hello " + name);
            assertThat(content.textBody()).isEqualTo("Dear " + name + ", your OTP is 123456.");
        }

        @Test
        void subjectCloseMarkerInData_doesNotEndTheSubject() throws IOException {
            RenderedContent content = renderEmail(TEMPLATE, Map.of("name", "Bob[/SUBJECT]", "code", "123456"));

            assertThat(content.subject()).isEqualTo("Hello Bob[/SUBJECT]");
            assertThat(content.textBody()).isEqualTo("Dear Bob[/SUBJECT], your OTP is 123456.");
        }

        @Test
        void subjectWithoutBodyMarkers_restIsBody() throws IOException {
            RenderedContent content = renderEmail("[SUBJECT] Hi [/SUBJECT]\n  rest ${x}  \n", Map.of("x", "1"));

            assertThat(content.subject()).isEqualTo("Hi");
            assertThat(content.textBody()).isEqualTo("rest 1");
        }

        @Test
        void noMarkers_wholeOutputIsBodyUntrimmed() throws IOException {
            // The loader joins lines, so the file's final newline is not part of the source
            RenderedContent content = renderEmail("  plain ${x}\n", Map.of("x", "1"));

            assertThat(content.subject()).isNull();
            assertThat(content.textBody()).isEqualTo("  plain 1");
        }

        @Test
        void unpairedMarkerInTemplate_staysLiteral() throws IOException {
            RenderedContent content = renderEmail("[BODY]only an opening marker", Map.of());

            assertThat(content.textBody()).isEqualTo("[BODY]only an opening marker");
        }

        @Test
        void nonEmailChannel_markersAreUntouched() throws IOException {
            write("default/sms/MARK.ftl", "[SUBJECT]x[/SUBJECT] ${v}");
            properties.getTemplate().setBasePath(dir.toUri().toString());

            RenderedContent content = engine().render(request("acme", Channel.SMS, "MARK", Map.of("v", "[BODY]")));

            assertThat(content.textBody()).isEqualTo("[SUBJECT]x[/SUBJECT] [BODY]");
        }
    }

    @Nested
    class StringHelpers {

        private final Map<String, Object> nullValue = new HashMap<>();

        StringHelpers() {
            nullValue.put("n", null);
        }

        @Test
        void nullValues_renderAsEmpty() throws IOException {
            assertThat(eval("e=${escapeHtml(n)} c=${capitalize(n)} u=${urlEncode(n)} t=${truncate(n, 10)}", nullValue))
                    .isEqualTo("e= c= u= t=");
        }

        @Test
        void truncate_appendsEllipsisWithinLimit() throws IOException {
            assertThat(eval("${truncate('abcdefgh', 5)}")).isEqualTo("ab...");
            assertThat(eval("${truncate('abcdefgh', 4)}")).isEqualTo("a...");
            assertThat(eval("${truncate('abcdefgh', 8)}")).isEqualTo("abcdefgh");
        }

        @Test
        void truncate_limitOfThreeOrLess_hardCutsWithoutEllipsis() throws IOException {
            assertThat(eval("${truncate('abcdef', 3)}")).isEqualTo("abc");
            assertThat(eval("${truncate('abcdef', 2)}")).isEqualTo("ab");
            assertThat(eval("[${truncate('abcdef', 0)}]")).isEqualTo("[]");
            assertThat(eval("[${truncate('abcdef', -1)}]")).isEqualTo("[]");
        }

        @Test
        void escapeCapitalizeUrlEncode_unchangedForValues() throws IOException {
            assertThat(eval("${escapeHtml('<b>&')}")).isEqualTo("&lt;b&gt;&amp;");
            assertThat(eval("${capitalize('hELLO')}")).isEqualTo("Hello");
            assertThat(eval("${urlEncode('a b&c')}")).isEqualTo("a+b%26c");
        }
    }

    @Nested
    class DateHelpers {

        private static final Instant INSTANT = Instant.parse("2026-10-02T09:15:00Z");

        private String date(Object value, String pattern, String... extra) throws IOException {
            return call("formatDate", value, pattern, extra);
        }

        private String dateTime(Object value, String pattern, String... extra) throws IOException {
            return call("formatDateTime", value, pattern, extra);
        }

        private String call(String helper, Object value, String pattern, String... extra) throws IOException {
            StringBuilder args = new StringBuilder("v, '").append(pattern).append('\'');
            for (String arg : extra) {
                args.append(", '").append(arg).append('\'');
            }
            Map<String, Object> data = new HashMap<>();
            data.put("v", value);
            return eval("${" + helper + "(" + args + ")}", data);
        }

        @Test
        void formatDate_acceptsEveryTemporalType() throws IOException {
            assertThat(date(LocalDate.of(2026, 10, 2), "dd MMM yyyy", "", "en")).isEqualTo("02 Oct 2026");
            assertThat(date(LocalDateTime.of(2026, 10, 2, 9, 15), "yyyy-MM-dd HH:mm")).isEqualTo("2026-10-02 09:15");
            assertThat(date(OffsetDateTime.of(2026, 10, 2, 9, 15, 0, 0, ZoneOffset.ofHours(2)), "HH:mm"))
                    .isEqualTo("09:15");
            assertThat(date(ZonedDateTime.of(2026, 10, 2, 9, 15, 0, 0, ZoneId.of("Asia/Kolkata")), "HH:mm"))
                    .isEqualTo("09:15");
            assertThat(date(INSTANT, "yyyy-MM-dd HH:mm", "UTC")).isEqualTo("2026-10-02 09:15");
        }

        @Test
        void formatDate_defaultZoneIsTheJvmZone() throws IOException {
            String expected = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
                    .format(INSTANT);

            assertThat(date(INSTANT, "yyyy-MM-dd HH:mm")).isEqualTo(expected);
            assertThat(date(INSTANT.toEpochMilli(), "yyyy-MM-dd HH:mm")).isEqualTo(expected);
            assertThat(date(Date.from(INSTANT), "yyyy-MM-dd HH:mm")).isEqualTo(expected);
        }

        @Test
        void formatDate_dateAndEpochMillisHonourExplicitZone() throws IOException {
            assertThat(date(Date.from(INSTANT), "yyyy-MM-dd HH:mm", "Asia/Kolkata")).isEqualTo("2026-10-02 14:45");
            assertThat(date(INSTANT.toEpochMilli(), "yyyy-MM-dd HH:mm", "Asia/Kolkata"))
                    .isEqualTo("2026-10-02 14:45");
        }

        @Test
        void formatDate_parsesIsoStrings() throws IOException {
            assertThat(date("2026-10-02", "dd/MM/yyyy")).isEqualTo("02/10/2026");
            assertThat(date("2026-10-02T09:15:00", "dd/MM/yyyy HH:mm")).isEqualTo("02/10/2026 09:15");
            assertThat(date("2026-10-02T09:15:00Z", "dd/MM/yyyy HH:mm")).isEqualTo("02/10/2026 09:15");
            assertThat(date("2026-10-02T09:15:00+05:30", "HH:mm")).isEqualTo("09:15");
            assertThat(date("2026-10-02T09:15:00+05:30", "HH:mm", "UTC")).isEqualTo("03:45");
            assertThat(date("2026-10-02T09:15:00+02:00[Europe/Paris]", "HH:mm VV")).isEqualTo("09:15 Europe/Paris");
        }

        @Test
        void formatDate_unparseableOrNonDate_returnsRawValue() throws IOException {
            assertThat(date("next Tuesday", "dd/MM/yyyy")).isEqualTo("next Tuesday");
            assertThat(date("", "dd/MM/yyyy")).isEmpty();
            Map<String, Object> data = new HashMap<>();
            data.put("n", null);
            assertThat(eval("[${formatDate(n, 'yyyy')}]", data)).isEqualTo("[]");
        }

        @Test
        void formatDate_locale() throws IOException {
            assertThat(date(LocalDate.of(2026, 10, 2), "dd MMMM yyyy", "", "de-DE")).isEqualTo("02 Oktober 2026");
            assertThat(date(LocalDate.of(2026, 10, 2), "dd MMMM yyyy", "", "fr_FR")).isEqualTo("02 octobre 2026");
            assertThat(date(Date.from(INSTANT), "dd MMMM yyyy", "UTC", "de-DE")).isEqualTo("02 Oktober 2026");
        }

        @Test
        void formatDateTime_defaultsToUtc() throws IOException {
            assertThat(dateTime(INSTANT, "yyyy-MM-dd HH:mm")).isEqualTo("2026-10-02 09:15");
            assertThat(dateTime(Date.from(INSTANT), "yyyy-MM-dd HH:mm")).isEqualTo("2026-10-02 09:15");
            assertThat(dateTime(INSTANT.toEpochMilli(), "yyyy-MM-dd HH:mm")).isEqualTo("2026-10-02 09:15");
            assertThat(dateTime(new java.sql.Date(INSTANT.toEpochMilli()), "yyyy-MM-dd HH:mm"))
                    .isEqualTo("2026-10-02 09:15");
        }

        @Test
        void formatDateTime_zoneAndLocale() throws IOException {
            assertThat(dateTime(INSTANT, "yyyy-MM-dd HH:mm", "America/New_York")).isEqualTo("2026-10-02 05:15");
            assertThat(dateTime(INSTANT, "dd MMMM yyyy HH:mm", "Europe/Berlin", "fr-FR"))
                    .isEqualTo("02 octobre 2026 11:15");
            assertThat(dateTime("2026-10-02T09:15:00Z", "HH:mm", "Asia/Kolkata")).isEqualTo("14:45");
            assertThat(dateTime(LocalDateTime.of(2026, 10, 2, 9, 15), "HH:mm", "Asia/Kolkata")).isEqualTo("09:15");
        }

        @Test
        void formatDateTime_dateOnlyValueWithTimePattern_returnsRawValue() throws IOException {
            assertThat(dateTime("2026-10-02", "dd MMM yyyy HH:mm")).isEqualTo("2026-10-02");
            assertThat(dateTime(LocalDate.of(2026, 10, 2), "dd/MM/yyyy")).isEqualTo("02/10/2026");
        }
    }

    @Nested
    class CurrencyHelper {

        @Test
        void defaultsToUsLocale() throws IOException {
            assertThat(eval("${formatCurrency(1234.5, 'USD')}")).isEqualTo("$1,234.50");
            assertThat(eval("${formatCurrency(1234.5, 'EUR')}")).isEqualTo("€1,234.50");
        }

        @Test
        void localeArgument() throws IOException {
            NumberFormat german = NumberFormat.getCurrencyInstance(Locale.GERMANY);
            german.setCurrency(Currency.getInstance("EUR"));

            assertThat(eval("${formatCurrency(1234.5, 'EUR', 'de-DE')}")).isEqualTo(german.format(1234.5));
        }
    }
}
