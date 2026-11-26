package com.lazydevs.notification.core.template;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.core.config.NotificationProperties;
import lazydevs.mapper.utils.engine.TemplateEngine;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-26: the engine's own FreeMarker configuration renders existing templates exactly as
 * the 1.2 path did, which handed the same source and model to persistence-utils'
 * {@code TemplateEngine.generate}. Each case renders through both and compares the whole
 * {@link RenderedContent}.
 */
class NotificationTemplateEngineEquivalenceTest {

    @TempDir
    Path dir;

    static Stream<Arguments> templates() {
        String escapedEmail = """
                [SUBJECT]Order #${orderId} for ${customer.name}[/SUBJECT]
                [BODY]
                <p>Hi ${customer.name}, note: ${note}</p>
                <p>${escapeHtml(note)} ${trusted?no_esc}</p>
                [/BODY]""";
        String email = """
                [SUBJECT]Order #${orderId} for ${customer.name}[/SUBJECT]
                [BODY]
                <html><body>
                <p>Hi ${customer.name}, note: ${note}</p>
                <p>${escapeHtml(note)} ${trusted}</p>
                <table><#list items as item><tr><td>${item?index}</td><td>${item.sku}</td><td>${item.price}</td></tr></#list></table>
                </body></html>
                [/BODY]
                [TEXT]
                Hi ${customer.name}, ${items?size} item(s), note ${note}
                [/TEXT]""";
        String plainEmail = """
                [SUBJECT]Hello ${customer.name?upper_case}[/SUBJECT]
                Dear ${capitalize(customer.name)},
                total ${formatCurrency(total, 'EUR', 'de-DE')} (${total}), ${defaultValue(missing!'', 'n/a')}
                <#if total gt 100>big<#else>small</#if> order""";
        String builtIns = """
                <#assign doubled = count * 2>
                ${count} ${doubled} ${ratio} ${big} ${decimal} ${flag?c} ${flag?string('yes', 'no')}
                ${count?string('0.00')} ${big?string.number} ${customer.name?length} ${customer.name?has_content?c}
                ${(customer.nick)!'no nick'} ${customer.nick???c} ${note?html} ${note?url('UTF-8')} ${items?first.sku}
                <#list items as item>${item.sku}<#sep>, </#list> <#list customer?keys?sort as k>${k}<#sep>|</#list>
                ${when?string('yyyy-MM-dd HH:mm:ss zzz')} ${'a,b'?split(',')?join('-')}""";
        String helpers = """
                <#macro badge text>[${text}]</#macro><#function twice n><#return n * 2></#function>
                <@badge text=customer.name/> ${twice(count)}
                ${formatDate(instant, 'yyyy-MM-dd HH:mm', 'UTC')} ${formatDate(day, 'dd MMM yyyy', '', 'de-DE')}
                ${formatDateTime(instant, 'yyyy-MM-dd HH:mm:ss')} ${formatDate(when, 'yyyy-MM-dd', 'Asia/Kolkata')}
                ${truncate(longText, 10)} ${urlEncode(note)} ${escapeHtml(note)} ${trim('  padded  ')}
                ${serde_serialize(customer)} ${eval("${'evaluated'}")}""";
        return Stream.of(
                Arguments.of("html email, auto-escape off", Channel.EMAIL, false, email),
                Arguments.of("html email, auto-escape on", Channel.EMAIL, true, email),
                Arguments.of("html email with ?no_esc, auto-escape on", Channel.EMAIL, true, escapedEmail),
                Arguments.of("plain email, auto-escape off", Channel.EMAIL, false, plainEmail),
                Arguments.of("plain email, auto-escape on", Channel.EMAIL, true, plainEmail),
                Arguments.of("built-ins and number formats", Channel.SMS, false, builtIns),
                Arguments.of("macros, functions and helpers", Channel.PUSH, false, helpers));
    }

    private static Map<String, Object> model() {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", "ada lovelace");
        customer.put("tier", "gold");
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", "A-17");
        data.put("customer", customer);
        data.put("note", "Tom & Jerry <script>\"x\"</script>");
        data.put("trusted", "<b>bold</b>");
        data.put("items", List.of(Map.of("sku", "S1", "price", 9.5), Map.of("sku", "S2", "price", 1200)));
        data.put("total", 1234.5);
        data.put("count", 7);
        data.put("ratio", 0.125);
        data.put("big", 12345678.9);
        data.put("decimal", new BigDecimal("1000000.010"));
        data.put("flag", true);
        data.put("when", new Date(1_700_000_000_000L));
        data.put("instant", Instant.parse("2026-11-26T10:15:30Z"));
        data.put("day", LocalDate.of(2026, 3, 9));
        data.put("longText", "The quick brown fox jumps");
        return data;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("templates")
    void rendersExactlyLikeThePersistenceUtilsPath(String name, Channel channel, boolean autoEscape, String source)
            throws IOException {
        Path file = dir.resolve("default/" + channel.name().toLowerCase() + "/T.ftl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        NotificationProperties properties = new NotificationProperties();
        properties.getTemplate().setBasePath(dir.toUri().toString());
        properties.getTemplate().setAutoEscape(autoEscape);

        RenderedContent owned = new NotificationTemplateEngine(properties, new DefaultResourceLoader())
                .render(request(channel));
        RenderedContent legacy = new NotificationTemplateEngine(properties, new DefaultResourceLoader()) {
            @Override
            String process(RenderTarget target, String templateSource, Map<String, Object> model) {
                return TemplateEngine.getInstance().generate(templateSource, model);
            }
        }.render(request(channel));

        assertThat(owned).as(name).isEqualTo(legacy);
        assertThat(owned.textBody() != null ? owned.textBody() : owned.htmlBody()).isNotBlank();
    }

    private static NotificationRequest request(Channel channel) {
        return NotificationRequest.builder()
                .requestId("r-1")
                .tenantId("acme")
                .templateId("T")
                .channel(channel)
                .templateData(model())
                .build();
    }
}
