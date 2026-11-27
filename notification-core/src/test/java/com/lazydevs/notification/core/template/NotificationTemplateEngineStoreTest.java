package com.lazydevs.notification.core.template;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.TemplateNotFoundException;
import com.lazydevs.notification.api.exception.TemplateRenderException;
import com.lazydevs.notification.api.exception.TemplateRenderException.Reason;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.template.TemplateDocument;
import com.lazydevs.notification.api.template.TemplateKey;
import com.lazydevs.notification.api.template.TemplateSource;
import com.lazydevs.notification.api.template.TemplateVersion;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.template.store.InMemoryTemplateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * DD-38: the engine layers the template store over the built-in files, for top-level
 * templates and includes, and drops cached copies when told a key changed.
 */
class NotificationTemplateEngineStoreTest {

    private static final String TENANT = "initech";

    @TempDir
    Path dir;

    private final NotificationProperties properties = new NotificationProperties();
    private InMemoryTemplateStore store;
    private NotificationTemplateEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        properties.getTemplate().setBasePath(dir.toUri().toString());
        properties.getTemplate().setLegacyHelpers(false);
        write("default/sms/T.ftl", "built-in default ${name}");
        write(TENANT + "/sms/T.ftl", "built-in tenant ${name}");
        store = new InMemoryTemplateStore(10_000);
        engine = new NotificationTemplateEngine(properties,
                new ResourceLoaderTemplateSource(new DefaultResourceLoader(), properties), store);
    }

    private void write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void publish(TemplateKey key, String content) {
        long revision = store.findDraft(key).map(TemplateVersion::revision).orElse(0L);
        TemplateVersion draft = store.saveDraft(key, TemplateVersion.FREEMARKER, content, null, revision, "ed", null);
        store.publish(key, draft.revision(), "ed", "note");
        engine.evict(key);
    }

    private String render(String tenant, String templateId) {
        return engine.render(NotificationRequest.builder()
                .tenantId(tenant)
                .channel(Channel.SMS)
                .templateId(templateId)
                .templateData(Map.of("name", "Ada"))
                .build()).textBody();
    }

    /** The resolution table of the design: tenant level first, store before file at each level. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "no override anywhere           | other    | -        | -      | built-in default Ada",
            "default override               | other    | default  | -      | store default Ada",
            "default and tenant override    | other    | default  | other  | store other Ada",
            "tenant file beats default store| " + TENANT + " | default  | -      | built-in tenant Ada",
            "tenant store beats tenant file | " + TENANT + " | default  | " + TENANT + " | store " + TENANT + " Ada"})
    void resolvesTenantThenDefaultWithTheStoreFirstAtEachLevel(String name, String tenant, String defaultOverride,
                                                               String tenantOverride, String expected) {
        if (!"-".equals(defaultOverride)) {
            publish(TemplateKey.of(null, "sms", "T"), "store default ${name}");
        }
        if (!"-".equals(tenantOverride)) {
            publish(TemplateKey.of(tenantOverride, "sms", "T"), "store " + tenantOverride + " ${name}");
        }

        assertThat(render(tenant, "T")).as(name).isEqualTo(expected);
    }

    @Test
    void storeOnlyTemplateRendersAndMissingEverywhereIsNotFound() {
        publish(TemplateKey.of(null, "sms", "NEW"), "brand new ${name}");

        assertThat(render("acme", "NEW")).isEqualTo("brand new Ada");
        assertThatThrownBy(() -> render("acme", "MISSING")).isInstanceOf(TemplateNotFoundException.class);
    }

    @Test
    void draftsAndJsonContentAreNeverRendered() {
        TemplateKey key = TemplateKey.of(null, "sms", "T");
        store.saveDraft(key, TemplateVersion.FREEMARKER, "draft ${name}", null, 0, "ed", null);
        assertThat(render("other", "T")).isEqualTo("built-in default Ada");

        TemplateKey json = TemplateKey.of(null, "sms", "J");
        TemplateVersion draft = store.saveDraft(json, TemplateVersion.JSON, "{}", null, 0, "ed", null);
        store.publish(json, draft.revision(), "ed", "n");
        assertThatThrownBy(() -> render("other", "J")).isInstanceOf(TemplateNotFoundException.class);
    }

    @Test
    void cachedTemplateIsServedUntilEvicted() {
        TemplateKey key = TemplateKey.of(null, "sms", "T");
        assertThat(render("other", "T")).isEqualTo("built-in default Ada");
        TemplateVersion draft = store.saveDraft(key, TemplateVersion.FREEMARKER, "v1 ${name}", null, 0, "ed", null);
        store.publish(key, draft.revision(), "ed", "n");

        assertThat(render("other", "T")).as("still cached").isEqualTo("built-in default Ada");
        engine.templateChanged(key);
        assertThat(render("other", "T")).isEqualTo("v1 Ada");
    }

    @Test
    void defaultKeyEvictsEveryTenantAndTenantKeyOnlyThatTenant() {
        TemplateKey defaults = TemplateKey.of(null, "sms", "T");
        TemplateKey acme = TemplateKey.of("acme", "sms", "T");
        assertThat(render("acme", "T")).isEqualTo("built-in default Ada");
        assertThat(render("globex", "T")).isEqualTo("built-in default Ada");
        assertThat(engine.cachedTemplateCount()).isEqualTo(2);

        engine.evict(acme);
        assertThat(engine.cachedTemplateCount()).isEqualTo(1);
        engine.evict(TemplateKey.of("acme", "sms", "OTHER"));
        assertThat(engine.cachedTemplateCount()).isEqualTo(1);
        engine.evict(defaults);
        assertThat(engine.cachedTemplateCount()).isZero();

        render("acme", "T");
        engine.allTemplatesChanged();
        assertThat(engine.cachedTemplateCount()).isZero();
    }

    @Test
    void aLoadThatOverlapsAnEvictionIsNotLeftCached() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        TemplateSource slow = new TemplateSource() {
            @Override
            public Optional<TemplateDocument> find(TemplateKey key) {
                if (loads.incrementAndGet() == 1) {
                    loading.countDown();
                    awaitQuietly(release);
                }
                return Optional.of(TemplateDocument.builtIn(key.name(), "v" + loads.get(), -1));
            }

            @Override
            public Optional<TemplateDocument> findInclude(String name, Charset charset) {
                return Optional.empty();
            }
        };
        NotificationTemplateEngine racing = new NotificationTemplateEngine(properties, slow, store);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = pool.submit(() -> racing.render(NotificationRequest.builder()
                    .tenantId("acme").channel(Channel.SMS).templateId("T").build()).textBody());
            assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
            racing.evict(TemplateKey.of(null, "sms", "T"));
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).as("the racing render still completes").isEqualTo("v1");
            assertThat(racing.cachedTemplateCount()).as("but its load is not kept").isZero();
        } finally {
            pool.shutdownNow();
        }
        assertThat(racing.render(NotificationRequest.builder().tenantId("acme").channel(Channel.SMS)
                .templateId("T").build()).textBody()).isEqualTo("v2");
        assertThat(racing.cachedTemplateCount()).isEqualTo(1);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void includesResolveThroughTheStoreFirst() throws IOException {
        write("default/sms/MAIN.ftl", "Hi <#include \"PART.ftl\">");
        write("default/sms/PART.ftl", "file part");
        assertThat(render("acme", "MAIN")).isEqualTo("Hi file part");

        publish(TemplateKey.of(null, "sms", "PART"), "store part");
        assertThat(render("acme", "MAIN")).isEqualTo("Hi store part");
    }

    @Test
    void storedTemplatesRenderWithTheSaferClassResolver() {
        publish(TemplateKey.of(null, "sms", "EXEC"),
                "<#assign ex = \"freemarker.template.utility.Execute\"?new()>${ex(\"id\")}");

        TemplateRenderException e = catchThrowableOfType(TemplateRenderException.class, () -> render("acme", "EXEC"));

        assertThat(e.getReason()).isEqualTo(Reason.MODEL_INVALID);
        assertThat(e.getMessage()).contains("Execute");
    }

    @Test
    void storeOverrideNormalisesLineBreaksLikeTheBuiltInReader() throws IOException {
        write("default/sms/CRLF.ftl", "a\r\nb\r\n");
        String fromFile = render("acme", "CRLF");

        publish(TemplateKey.of("acme", "sms", "CRLF"), "a\r\nb\r\n");

        assertThat(render("acme", "CRLF")).isEqualTo(fromFile).isEqualTo("a\nb");
    }

    @Test
    void storeRequiresLegacyHelpersOff() {
        properties.getTemplate().setLegacyHelpers(true);
        ResourceLoaderTemplateSource source = new ResourceLoaderTemplateSource(new DefaultResourceLoader(), properties);

        assertThatThrownBy(() -> new NotificationTemplateEngine(properties, source, store))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notification.template.legacy-helpers=false");
        assertThat(new NotificationTemplateEngine(properties, source, null).isStoreConfigured()).isFalse();
    }

    @Test
    void renderSourceReportsTheLineAndColumnOfASyntaxError() {
        TemplateKey key = TemplateKey.of(null, "email", "WELCOME");

        TemplateRenderException e = catchThrowableOfType(TemplateRenderException.class, () ->
                engine.renderSource(key, "[SUBJECT]Hi[/SUBJECT]\n[BODY]\n  ${name + }\n[/BODY]", Map.of(), false));

        assertThat(e.getReason()).isEqualTo(Reason.INVALID_TEMPLATE);
        assertThat(e.getLineNumber()).isEqualTo(3);
        assertThat(e.getColumnNumber()).isEqualTo(12);
    }

    @Test
    void renderSourceReportsTheVariablePathAndLocationOfAModelError() {
        TemplateKey key = TemplateKey.of(null, "sms", "T");

        TemplateRenderException e = catchThrowableOfType(TemplateRenderException.class, () ->
                engine.renderSource(key, "line one\nHello ${user.name}", Map.of("user", Map.of()), false));

        assertThat(e.getReason()).isEqualTo(Reason.MODEL_INVALID);
        assertThat(e.getVariablePath()).isEqualTo("user.name");
        // The failing expression, user.name, starts in column 9
        assertThat(e.getLineNumber()).isEqualTo(2);
        assertThat(e.getColumnNumber()).isEqualTo(9);
    }

    @Test
    void renderSourceDropsTheColumnOnALineTheMarkersChanged() {
        TemplateKey key = TemplateKey.of(null, "email", "T");

        TemplateRenderException e = catchThrowableOfType(TemplateRenderException.class, () ->
                engine.renderSource(key, "[SUBJECT]Hi ${missing}[/SUBJECT]", Map.of(), false));

        assertThat(e.getLineNumber()).isEqualTo(1);
        assertThat(e.getColumnNumber()).isNull();
    }

    @Test
    void renderSourceRendersEmailPartsAndValidatesOnlyWhenAsked() {
        TemplateKey key = TemplateKey.of("acme", "email", "T");

        RenderedContent content = engine.renderSource(key,
                "[SUBJECT]Hi ${name}[/SUBJECT][BODY]<p>${name}</p>[/BODY][TEXT]${name}[/TEXT]",
                Map.of("name", "Ada"), true);
        assertThat(content.subject()).isEqualTo("Hi Ada");
        assertThat(content.htmlBody()).isEqualTo("<p>Ada</p>");
        assertThat(content.textBody()).isEqualTo("Ada");

        assertThat(engine.renderSource(key, "[SUBJECT][/SUBJECT]body", Map.of(), false).subject()).isEmpty();
        TemplateRenderException e = catchThrowableOfType(TemplateRenderException.class, () ->
                engine.renderSource(key, "[SUBJECT][/SUBJECT]body", Map.of(), true));
        assertThat(e.getReason()).isEqualTo(Reason.EMPTY_OUTPUT);
    }

    @Test
    void includeErrorsPointAtTheIncludeDirective() throws IOException {
        write("default/sms/BROKEN.ftl", "${", "");
        TemplateKey key = TemplateKey.of(null, "sms", "T");

        TemplateRenderException e = catchThrowableOfType(TemplateRenderException.class, () ->
                engine.renderSource(key, "x <#include \"BROKEN.ftl\">", Map.of(), false));

        assertThat(e.getReason()).isEqualTo(Reason.INVALID_TEMPLATE);
        assertThat(e.getLineNumber()).isEqualTo(1);
        assertThat(e.getMessage()).contains("BROKEN.ftl");
    }

    private void write(String path, String content, String suffix) throws IOException {
        write(path, content + suffix);
    }
}
