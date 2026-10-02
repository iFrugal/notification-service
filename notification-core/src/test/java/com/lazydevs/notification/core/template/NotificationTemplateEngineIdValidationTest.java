package com.lazydevs.notification.core.template;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.exception.TemplateNotFoundException;
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
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tenant, channel and template ids become path segments, so ids that could escape the base
 * path are rejected before the {@link ResourceLoader} is asked for anything.
 */
class NotificationTemplateEngineIdValidationTest {

    @TempDir
    Path dir;

    private final NotificationProperties properties = new NotificationProperties();
    private final RecordingResourceLoader loader = new RecordingResourceLoader();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(NotificationTemplateEngine.class);

    @BeforeEach
    void setUp() throws IOException {
        // Base path is dir/base/; the sentinels sit beside it, where a traversal would land
        Path base = Files.createDirectories(dir.resolve("base"));
        properties.getTemplate().setBasePath(base.toUri().toString());
        write("etc/sms/OTP.ftl", "SENTINEL");
        write("etc/sms/secret.ftl", "SENTINEL");
        write("base/default/sms/secret.ftl", "SENTINEL");
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = dir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String render(String tenantId, String templateId) {
        return new NotificationTemplateEngine(properties, loader).render(NotificationRequest.builder()
                .requestId("r-1")
                .tenantId(tenantId)
                .templateId(templateId)
                .channel(Channel.SMS)
                .templateData(new HashMap<>(Map.of()))
                .build()).textBody();
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../etc", "../etc", "acme/../../etc", "acme/evil", "acme\\evil", "..", ".hidden", ""})
    void unsafeTenantId_isRejectedWithoutTouchingTheLoader(String tenantId) {
        assertThatThrownBy(() -> render(tenantId, "OTP"))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasMessageContaining("invalid tenantId");

        assertThat(loader.requested).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"..\\secret", "..\\..\\etc\\sms\\secret", "x..y", "../etc/sms/secret", "a/b", "sp ace"})
    void unsafeTemplateId_isRejectedWithoutTouchingTheLoader(String templateId) {
        assertThatThrownBy(() -> render("acme", templateId))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasMessageContaining("invalid templateId");

        assertThat(loader.requested).isEmpty();
    }

    @Test
    void tooLongId_isRejected() {
        assertThatThrownBy(() -> render("a".repeat(129), "OTP"))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasMessageContaining("invalid tenantId");
        assertThat(loader.requested).isEmpty();
    }

    @Test
    void missingTemplateId_isRejected() {
        assertThatThrownBy(() -> render("acme", null))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasMessageContaining("invalid templateId");
        assertThat(loader.requested).isEmpty();
    }

    @Test
    void rejection_neverEchoesTheValueAndLogsOnlyAMaskedPrefix() {
        assertThatThrownBy(() -> render("../../etc/passwd\r\nFAKE LOG LINE", "OTP"))
                .isInstanceOf(TemplateNotFoundException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("..").doesNotContain("passwd"));

        assertThat(warnings()).singleElement().satisfies(message -> assertThat(message)
                .contains("tenantId")
                .contains("'../../et...'")
                .doesNotContain("passwd")
                .doesNotContain("FAKE"));
    }

    @Test
    void mask_replacesControlCharacters() {
        assertThat(NotificationTemplateEngine.mask("a\nb\u0000c")).isEqualTo("a?b?c");
        assertThat(NotificationTemplateEngine.mask("123456789")).isEqualTo("12345678...");
        assertThat(NotificationTemplateEngine.mask(null)).isEqualTo("null");
    }

    @Test
    void idsWithDotsDashesAndUnderscores_stillResolve() throws IOException {
        write("base/acme-eu.1/sms/order.shipped_v2-final.ftl", "Tenant ${1 + 1}");
        write("base/default/sms/ORDER.SHIPPED-v3.ftl", "Default");

        assertThat(render("acme-eu.1", "order.shipped_v2-final")).isEqualTo("Tenant 2");
        assertThat(render("acme-eu.1", "ORDER.SHIPPED-v3")).isEqualTo("Default");
        assertThat(warnings()).isEmpty();
    }

    @Test
    void legacyFallback_isBuiltFromTheSameValidatedIds() throws IOException {
        write("base/templates/default/sms/OTP.ftl", "Legacy");

        assertThat(render("acme", "OTP")).isEqualTo("Legacy");
        assertThat(loader.requested).allSatisfy(location -> assertThat(location)
                .startsWith(properties.getTemplate().getBasePath())
                .doesNotContain(".."));
    }

    @Test
    void sentinelOutsideTheBasePath_isNeverRead() {
        assertThatThrownBy(() -> render("../etc", "OTP")).isInstanceOf(TemplateNotFoundException.class);
        assertThatThrownBy(() -> render("acme", "../../etc/sms/secret")).isInstanceOf(TemplateNotFoundException.class);

        assertThat(loader.requested).isEmpty();
    }

    /**
     * Delegates to a {@link DefaultResourceLoader} and records every location requested.
     */
    private static final class RecordingResourceLoader implements ResourceLoader {
        private final DefaultResourceLoader delegate = new DefaultResourceLoader();
        private final List<String> requested = new CopyOnWriteArrayList<>();

        @Override
        public Resource getResource(String location) {
            requested.add(location);
            return delegate.getResource(location);
        }

        @Override
        public ClassLoader getClassLoader() {
            return delegate.getClassLoader();
        }
    }
}
