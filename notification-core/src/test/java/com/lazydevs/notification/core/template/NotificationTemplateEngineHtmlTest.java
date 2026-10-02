package com.lazydevs.notification.core.template;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.core.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTML detection, the optional {@code [TEXT]} part and {@code notification.template.auto-escape}.
 */
class NotificationTemplateEngineHtmlTest {

    private static final AtomicInteger IDS = new AtomicInteger();

    @TempDir
    Path dir;

    private final NotificationProperties properties = new NotificationProperties();

    @BeforeEach
    void setUp() {
        properties.getTemplate().setBasePath(dir.toUri().toString());
    }

    private RenderedContent render(String source, Map<String, Object> data) throws IOException {
        String id = "T" + IDS.incrementAndGet();
        Path file = dir.resolve("default/email/" + id + ".ftl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return new NotificationTemplateEngine(properties, new DefaultResourceLoader())
                .render(NotificationRequest.builder()
                        .requestId("r-1")
                        .tenantId("acme")
                        .notificationType(id)
                        .channel(Channel.EMAIL)
                        .templateData(new HashMap<>(data))
                        .build());
    }

    @Nested
    class HtmlDetection {

        @ParameterizedTest
        @ValueSource(strings = {
                "<table><tr><td>x</td></tr></table>",
                "line one<br/>line two",
                "<span>x</span>",
                "see <a href=\"https://example.com\">here</a>",
                "<!DOCTYPE html><title>x</title>",
                "<P>upper case</P>",
                "<DIV>upper case</DIV>",
                "<html><body>x</body></html>",
                "<p>x</p>"})
        void markupBody_isSentAsHtml(String body) throws IOException {
            RenderedContent content = render("[SUBJECT]s[/SUBJECT][BODY]" + body + "[/BODY]", Map.of());

            assertThat(content.htmlBody()).isEqualTo(body);
            assertThat(content.textBody()).isNull();
        }

        @Test
        void plainBody_isSentAsText() throws IOException {
            RenderedContent content = render("[SUBJECT]s[/SUBJECT][BODY]1 < 2 and a<b[/BODY]", Map.of());

            assertThat(content.textBody()).isEqualTo("1 < 2 and a<b");
            assertThat(content.htmlBody()).isNull();
        }
    }

    @Nested
    class TextPart {

        @Test
        void textSection_producesHtmlAndTextBodies() throws IOException {
            RenderedContent content = render(
                    "[SUBJECT]Hi ${name}[/SUBJECT]\n[BODY]\n<p>Hi ${name}</p>\n[/BODY]\n[TEXT]\nHi ${name}\n[/TEXT]\n",
                    Map.of("name", "Ada"));

            assertThat(content.subject()).isEqualTo("Hi Ada");
            assertThat(content.htmlBody()).isEqualTo("<p>Hi Ada</p>");
            assertThat(content.textBody()).isEqualTo("Hi Ada");
        }

        @Test
        void textSectionWithoutBodyMarkers_restAfterSubjectIsHtml() throws IOException {
            RenderedContent content = render(
                    "[SUBJECT]s[/SUBJECT]\n<p>Hello</p>\n[TEXT]Hello[/TEXT]\n", Map.of());

            assertThat(content.htmlBody()).isEqualTo("<p>Hello</p>");
            assertThat(content.textBody()).isEqualTo("Hello");
        }

        @Test
        void textSection_bodyIsTheHtmlPartEvenWithoutTags() throws IOException {
            RenderedContent content = render("[BODY]Hello[/BODY][TEXT]Hello in text[/TEXT]", Map.of());

            assertThat(content.htmlBody()).isEqualTo("Hello");
            assertThat(content.textBody()).isEqualTo("Hello in text");
        }

        @Test
        void textMarkersInData_doNotCreateATextPart() throws IOException {
            RenderedContent content = render("[SUBJECT]s[/SUBJECT][BODY]<p>${name}</p>[/BODY]",
                    Map.of("name", "[TEXT]phish[/TEXT]"));

            assertThat(content.htmlBody()).isEqualTo("<p>[TEXT]phish[/TEXT]</p>");
            assertThat(content.textBody()).isNull();
        }

        @Test
        void noTextSection_unchanged() throws IOException {
            RenderedContent content = render("[SUBJECT]s[/SUBJECT][BODY]<p>x</p>[/BODY]", Map.of());

            assertThat(content.htmlBody()).isEqualTo("<p>x</p>");
            assertThat(content.textBody()).isNull();
        }
    }

    @Nested
    class AutoEscapeOff {

        @Test
        void markupInData_isPrintedAsIs() throws IOException {
            RenderedContent content = render("[SUBJECT]Hi ${name}[/SUBJECT][BODY]<p>Hi ${name}</p>[/BODY]",
                    Map.of("name", "<b>Bob</b> & <script>x</script>"));

            assertThat(content.subject()).isEqualTo("Hi <b>Bob</b> & <script>x</script>");
            assertThat(content.htmlBody()).isEqualTo("<p>Hi <b>Bob</b> & <script>x</script></p>");
        }

        @Test
        void escapeHtml_returnsEscapedText() throws IOException {
            RenderedContent content = render("[BODY]<p>${escapeHtml(name)}</p>[/BODY]", Map.of("name", "<b>&"));

            assertThat(content.htmlBody()).isEqualTo("<p>&lt;b&gt;&amp;</p>");
        }

        @Test
        void textBodyWithMarkupFromData_isDetectedAsHtmlAsBefore() throws IOException {
            RenderedContent content = render("[BODY]Hi ${name}[/BODY]", Map.of("name", "<div>x</div>"));

            assertThat(content.htmlBody()).isEqualTo("Hi <div>x</div>");
        }
    }

    @Nested
    class AutoEscapeOn {

        @BeforeEach
        void enable() {
            properties.getTemplate().setAutoEscape(true);
        }

        @Test
        void interpolatedValues_areEscapedInHtmlBody() throws IOException {
            RenderedContent content = render("[SUBJECT]Hi ${name}[/SUBJECT][BODY]<p>Hi ${name}</p>[/BODY]",
                    Map.of("name", "<script>alert('x')</script> & co"));

            assertThat(content.htmlBody())
                    .isEqualTo("<p>Hi &lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; co</p>");
            assertThat(content.subject()).as("subject is a plain-text header")
                    .isEqualTo("Hi <script>alert('x')</script> & co");
        }

        @Test
        void escapeHtml_isNotEscapedTwice() throws IOException {
            RenderedContent content = render("[BODY]<p>${escapeHtml(name)} ${escapeHtml(escapeHtml(name))}</p>[/BODY]",
                    Map.of("name", "<b>&"));

            assertThat(content.htmlBody()).isEqualTo("<p>&lt;b&gt;&amp; &lt;b&gt;&amp;</p>");
        }

        @Test
        void escapeHtmlInSubject_printsEscapedTextAsBefore() throws IOException {
            RenderedContent content = render("[SUBJECT]${escapeHtml(name)}[/SUBJECT][BODY]<p>x</p>[/BODY]",
                    Map.of("name", "a&b"));

            assertThat(content.subject()).isEqualTo("a&amp;b");
        }

        @Test
        void helpersAcceptEscapedValues() throws IOException {
            RenderedContent content = render("[BODY]<p>${truncate(escapeHtml(name), 7)}|${capitalize(escapeHtml(name))}</p>[/BODY]",
                    Map.of("name", "abcdefghij"));

            assertThat(content.htmlBody()).isEqualTo("<p>abcd...|Abcdefghij</p>");
        }

        @Test
        void noEsc_passesTrustedMarkupThrough() throws IOException {
            RenderedContent content = render("[BODY]<div>${banner?no_esc} ${name}</div>[/BODY]",
                    Map.of("banner", "<b>Sale</b>", "name", "<i>"));

            assertThat(content.htmlBody()).isEqualTo("<div><b>Sale</b> &lt;i&gt;</div>");
        }

        @Test
        void textSection_isNotEscaped() throws IOException {
            RenderedContent content = render("[SUBJECT]s[/SUBJECT][BODY]<p>${n}</p>[/BODY][TEXT]Hi ${n}[/TEXT]",
                    Map.of("n", "A&B"));

            assertThat(content.htmlBody()).isEqualTo("<p>A&amp;B</p>");
            assertThat(content.textBody()).isEqualTo("Hi A&B");
        }

        @Test
        void bodyWithoutTagsButWithTextSection_isEscapedBecauseItIsTheHtmlPart() throws IOException {
            RenderedContent content = render("[BODY]Hi ${n}[/BODY][TEXT]Hi ${n}[/TEXT]",
                    Map.of("n", "<script>x</script>"));

            assertThat(content.htmlBody()).isEqualTo("Hi &lt;script&gt;x&lt;/script&gt;");
            assertThat(content.textBody()).isEqualTo("Hi <script>x</script>");
        }

        @Test
        void textSectionInsideImplicitBody_isNotEscaped() throws IOException {
            RenderedContent content = render("[SUBJECT]s[/SUBJECT]<p>${n}</p>[TEXT]Hi ${n}[/TEXT]<p>${n}</p>",
                    Map.of("n", "A&B"));

            assertThat(content.htmlBody()).isEqualTo("<p>A&amp;B</p><p>A&amp;B</p>");
            assertThat(content.textBody()).isEqualTo("Hi A&B");
        }

        @Test
        void plainTextBody_isNotEscapedAndDataCannotMakeItHtml() throws IOException {
            RenderedContent content = render("[SUBJECT]s[/SUBJECT][BODY]Hi ${name} & welcome[/BODY]",
                    Map.of("name", "<div>O'Brien</div>"));

            assertThat(content.textBody()).isEqualTo("Hi <div>O'Brien</div> & welcome");
            assertThat(content.htmlBody()).isNull();
        }

        @Test
        void templateWithoutMarkers_isEscapedWhenHtml() throws IOException {
            RenderedContent content = render("<p>${name}</p>", Map.of("name", "<b>"));

            assertThat(content.htmlBody()).isEqualTo("<p>&lt;b&gt;</p>");
        }

        @Test
        void ftlHeader_staysFirst() throws IOException {
            RenderedContent content = render("<#ftl strip_whitespace=true>\n<p>${name}</p>", Map.of("name", "<b>"));

            assertThat(content.htmlBody()).contains("<p>&lt;b&gt;</p>").doesNotContain("<b>");
        }
    }
}
