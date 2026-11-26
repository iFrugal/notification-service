package com.lazydevs.notification.core.template;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.TemplateNotFoundException;
import com.lazydevs.notification.api.exception.TemplateRenderException;
import com.lazydevs.notification.api.exception.TemplateRenderException.Reason;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.core.config.NotificationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * DD-26 and DD-27: render failures surface once, as {@link TemplateRenderException}, with a
 * reason and, for model errors, the data-model path of the value that broke the render.
 */
class NotificationTemplateEngineRenderErrorTest {

    @TempDir
    Path dir;

    private final NotificationProperties properties = new NotificationProperties();
    private final ListAppender<ILoggingEvent> freemarkerLog = new ListAppender<>();
    private final Logger freemarkerLogger = (Logger) LoggerFactory.getLogger("freemarker");

    @BeforeEach
    void setUp() {
        properties.getTemplate().setBasePath(dir.toUri().toString());
        freemarkerLog.start();
        freemarkerLogger.addAppender(freemarkerLog);
    }

    @AfterEach
    void tearDown() {
        freemarkerLogger.detachAppender(freemarkerLog);
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = dir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private NotificationTemplateEngine engine() {
        return new NotificationTemplateEngine(properties, new DefaultResourceLoader());
    }

    private static NotificationRequest request(Channel channel, Map<String, Object> data) {
        return NotificationRequest.builder()
                .requestId("r-1")
                .tenantId("acme")
                .templateId("T")
                .channel(channel)
                .templateData(new HashMap<>(data))
                .build();
    }

    private TemplateRenderException renderSmsFailure(String source, Map<String, Object> data) throws IOException {
        write("default/sms/T.ftl", source);
        return catchThrowableOfType(TemplateRenderException.class,
                () -> engine().render(request(Channel.SMS, data)));
    }

    private static Map<String, Object> user() {
        Map<String, Object> address = new HashMap<>(Map.of("street", "Main St"));
        Map<String, Object> user = new HashMap<>();
        user.put("nick", "");
        user.put("address", address);
        user.put("items", List.of(Map.of("sku", "S1"), Map.of("price", 3)));
        return Map.of("user", user, "count", 3);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "direct reference                  | ${user.name}                                                                                  | user.name",
            "missing root                      | Hi ${customer}                                                                                | customer",
            "nested map                        | ${user.address.city}                                                                          | user.address.city",
            "list element                      | ${user.items[1].sku}                                                                          | user.items[1].sku",
            "quoted key                        | ${user['first name']}                                                                         | user[\"first name\"]",
            "inside a function                 | <#function greet u><#return 'Hi ' + u.name></#function>${greet(user)}                         | user.name",
            "nested value through a function   | <#function city a><#return a.city?upper_case></#function>${city(user.address)}                | user.address.city",
            "inside a macro                    | <#macro card u>${u.name}</#macro><@card u=user/>                                              | user.name",
            "macro in a macro                  | <#macro inner a>${a.zip}</#macro><#macro outer u><@inner a=u.address/></#macro><@outer u=user/> | user.address.zip",
            "list item inside a macro          | <#macro row it>${it.sku}</#macro><#list user.items as item><@row it=item/></#list>            | user.items[1].sku",
            "?has_content guard falls through  | <#if user.nick?has_content>${user.nick}<#else>${user.name}</#if>                               | user.name",
            "?has_content in a macro           | <#macro m u><#if u.nick?has_content>${u.nick}<#else>${u.address.city}</#if></#macro><@m u=user/> | user.address.city",
            "wrong type                        | ${user.nick.first}                                                                            | user.nick",
            "wrong type of a scalar root       | ${count.value}                                                                                | count",
    })
    void modelError_reportsTheDataModelPath(String name, String source, String expectedPath) throws IOException {
        TemplateRenderException e = renderSmsFailure(source, user());

        assertThat(e.getReason()).isEqualTo(Reason.MODEL_INVALID);
        assertThat(e.getErrorCode()).isEqualTo("TEMPLATE_MODEL_INVALID");
        assertThat(e.getStatusCode()).isEqualTo(422);
        assertThat(e.getVariablePath()).isEqualTo(expectedPath);
        assertThat(e.getTenantId()).isEqualTo("acme");
        assertThat(e.getTemplateId()).isEqualTo("T");
    }

    @Test
    void modelError_messageIsOneLineWithLocationAndNoTipsOrStackTrace() throws IOException {
        TemplateRenderException e = renderSmsFailure("line one\n${user.name}", user());

        assertThat(e.getMessage())
                .startsWith("Template T for tenant acme could not be rendered: ")
                .contains("user.name", "in template \"default/sms/T.ftl\" at line 2")
                .doesNotContain("\n", "Tip:", "FTL stack trace", "----");
    }

    @Test
    void templateLocalValueNotFromTheDataModel_hasNoPath() throws IOException {
        TemplateRenderException e = renderSmsFailure("<#assign local = {'a': 1}>${local.b}", user());

        assertThat(e.getReason()).isEqualTo(Reason.MODEL_INVALID);
        assertThat(e.getVariablePath()).isNull();
    }

    @Test
    void scalarPassedToAMacro_hasNoPath() throws IOException {
        // The scalar has no identity to trace back to user.nick
        TemplateRenderException e = renderSmsFailure("<#macro m n>${n.first}</#macro><@m n=user.nick/>", user());

        assertThat(e.getVariablePath()).isNull();
    }

    @Test
    void nullArgumentToAFunction_isAModelErrorWithoutPath() throws IOException {
        // FreeMarker names the parameter, not the caller's expression
        TemplateRenderException e = renderSmsFailure(
                "<#function greet u><#return u.name></#function>${greet(user.missing)}", user());

        assertThat(e.getReason()).isEqualTo(Reason.MODEL_INVALID);
        assertThat(e.getVariablePath()).isNull();
        assertThat(e.getMessage()).contains("required parameter \"u\"", "at line 1");
    }

    @Test
    void functionReturningAMissingValue_blamesTheCallWhichHasNoPath() throws IOException {
        // #return of a missing value is legal; the interpolation of the call result fails
        TemplateRenderException e = renderSmsFailure(
                "<#function city a><#return a.city></#function>${city(user.address)}", user());

        assertThat(e.getReason()).isEqualTo(Reason.MODEL_INVALID);
        assertThat(e.getVariablePath()).isNull();
    }

    @Test
    void notAPlainPath_hasNoPath() throws IOException {
        TemplateRenderException e = renderSmsFailure("${(count + 1).x}", user());

        assertThat(e.getReason()).isEqualTo(Reason.MODEL_INVALID);
        assertThat(e.getVariablePath()).isNull();
    }

    @Test
    void helperThrowingAnUncheckedException_isAModelErrorWithLocation() throws IOException {
        TemplateRenderException e = renderSmsFailure("${formatCurrency(count, 'NOPE')}", user());

        assertThat(e.getReason()).isEqualTo(Reason.MODEL_INVALID);
        assertThat(e.getMessage()).contains("at line 1");
    }

    @Test
    void syntaxError_isInvalidTemplate() throws IOException {
        TemplateRenderException e = renderSmsFailure("<#if user.name>unclosed", user());

        assertThat(e.getReason()).isEqualTo(Reason.INVALID_TEMPLATE);
        assertThat(e.getErrorCode()).isEqualTo("TEMPLATE_INVALID");
        assertThat(e.getVariablePath()).isNull();
        assertThat(e.getMessage()).contains("default/sms/T.ftl").doesNotContain("\n");
    }

    @Test
    void missingInclude_isInvalidTemplate() throws IOException {
        TemplateRenderException e = renderSmsFailure("<#include 'nope.ftl'>", user());

        assertThat(e.getReason()).isEqualTo(Reason.INVALID_TEMPLATE);
        assertThat(e.getVariablePath()).isNull();
    }

    @Test
    void failuresAreNeverLoggedByFreemarker() throws IOException {
        renderSmsFailure("${user.name}", user());
        renderSmsFailure("<#include 'nope.ftl'>", user());

        assertThat(freemarkerLog.list).isEmpty();
    }

    @Test
    void missingTemplate_isStillTemplateNotFoundException() {
        assertThatThrownBy(() -> engine().render(request(Channel.SMS, Map.of())))
                .isInstanceOf(TemplateNotFoundException.class)
                .isInstanceOf(TemplateRenderException.class)
                .satisfies(t -> {
                    TemplateRenderException e = (TemplateRenderException) t;
                    assertThat(e.getReason()).isEqualTo(Reason.NOT_FOUND);
                    assertThat(e.getErrorCode()).isEqualTo("TEMPLATE_NOT_FOUND");
                    assertThat(e.getStatusCode()).isEqualTo(404);
                    assertThat(e.getTemplateId()).isEqualTo("T");
                    assertThat(e.getTenantId()).isEqualTo("acme");
                });
    }

    @Test
    void renderAndValidate_returnsSendableEmail() throws IOException {
        write("default/email/T.ftl", "[SUBJECT]Hi ${name}[/SUBJECT][BODY]Body[/BODY]");

        RenderedContent content = engine().renderAndValidate(request(Channel.EMAIL, Map.of("name", "Ada")));

        assertThat(content.subject()).isEqualTo("Hi Ada");
        assertThat(content.textBody()).isEqualTo("Body");
    }

    @Test
    void renderAndValidate_refusesBlankSubject() throws IOException {
        write("default/email/T.ftl", "[SUBJECT]${title!''}  [/SUBJECT][BODY]Body[/BODY]");

        assertThatThrownBy(() -> engine().renderAndValidate(request(Channel.EMAIL, Map.of())))
                .isInstanceOfSatisfying(TemplateRenderException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(Reason.EMPTY_OUTPUT);
                    assertThat(e.getErrorCode()).isEqualTo("TEMPLATE_EMPTY_OUTPUT");
                    assertThat(e.getMessage()).contains("subject is blank");
                    assertThat(e.getTemplateId()).isEqualTo("T");
                    assertThat(e.getTenantId()).isEqualTo("acme");
                });
    }

    @Test
    void renderAndValidate_refusesEmailWithoutSubjectSection() throws IOException {
        write("default/email/T.ftl", "Just a body");

        assertThatThrownBy(() -> engine().renderAndValidate(request(Channel.EMAIL, Map.of())))
                .isInstanceOfSatisfying(TemplateRenderException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.EMPTY_OUTPUT));
    }

    @Test
    void renderAndValidate_refusesEmptyBody() throws IOException {
        write("default/email/T.ftl", "[SUBJECT]Hi[/SUBJECT][BODY]<#if false>x</#if>  [/BODY]");

        assertThatThrownBy(() -> engine().renderAndValidate(request(Channel.EMAIL, Map.of())))
                .isInstanceOfSatisfying(TemplateRenderException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(Reason.EMPTY_OUTPUT);
                    assertThat(e.getMessage()).contains("body is empty");
                });
    }

    @Test
    void renderAndValidate_acceptsHtmlOnlyBody() throws IOException {
        write("default/email/T.ftl", "[SUBJECT]Hi[/SUBJECT][BODY]<p>Hello</p>[/BODY]");

        RenderedContent content = engine().renderAndValidate(request(Channel.EMAIL, Map.of()));

        assertThat(content.htmlBody()).isEqualTo("<p>Hello</p>");
    }

    @Test
    void renderAndValidate_usesTheDefaultTenantWhenTheRequestHasNone() throws IOException {
        write("default/email/T.ftl", "[SUBJECT] [/SUBJECT][BODY]Body[/BODY]");
        NotificationRequest request = request(Channel.EMAIL, Map.of());
        request.setTenantId(null);

        assertThatThrownBy(() -> engine().renderAndValidate(request))
                .isInstanceOfSatisfying(TemplateRenderException.class,
                        e -> assertThat(e.getTenantId()).isEqualTo(properties.getDefaultTenant()));
    }

    @Test
    void renderAndValidate_doesNotCheckOtherChannelsForEmptyOutput() throws IOException {
        write("default/sms/T.ftl", "<#if false>x</#if>");

        assertThat(engine().renderAndValidate(request(Channel.SMS, Map.of())).textBody()).isEmpty();
    }
}
