package com.lazydevs.notification.core.template.admin;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.push.PushContent;
import com.lazydevs.notification.api.template.TemplateKey;
import com.lazydevs.notification.api.template.TemplateProblem;
import com.lazydevs.notification.api.template.TemplateStoreException;
import com.lazydevs.notification.api.template.TemplateStoreException.Reason;
import com.lazydevs.notification.api.template.TemplateSummary;
import com.lazydevs.notification.api.template.TemplateVersion;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.push.PushContentRenderer;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import com.lazydevs.notification.core.template.ResourceLoaderTemplateSource;
import com.lazydevs.notification.core.template.store.InMemoryTemplateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * DD-38: the admin service's listing, layering, checks and guarded writes.
 */
class TemplateAdminServiceTest {

    private static final String WELCOME = """
            <#assign who = user.name>
            [SUBJECT]
            Welcome, ${who}
            [/SUBJECT]
            [BODY]
            <p>Hello ${who}</p>
            [/BODY]
            """;

    @TempDir
    Path dir;

    private final NotificationProperties properties = new NotificationProperties();
    private final List<TemplateKey> changed = new ArrayList<>();
    private InMemoryTemplateStore store;
    private NotificationTemplateEngine engine;
    private TemplateAdminService service;

    private static final TemplateKey DEFAULT_WELCOME = TemplateKey.of(null, "email", "WELCOME");
    private static final TemplateKey ACME_WELCOME = TemplateKey.of("acme", "email", "WELCOME");
    private static final Map<String, Object> MODEL = Map.of("user", Map.of("name", "Ada"));

    @BeforeEach
    void setUp() throws IOException {
        properties.getTemplate().setBasePath(dir.toUri().toString());
        properties.getTemplate().setLegacyHelpers(false);
        write("default/email/WELCOME.ftl", WELCOME);
        write("globex/email/WELCOME.ftl", "[SUBJECT]Globex[/SUBJECT]hi");
        write("default/sms/OTP.ftl", "Code ${code}");
        write("default/email/_layout.ftl", "partial, not listed");
        store = new InMemoryTemplateStore(4096);
        ResourceLoaderTemplateSource source = new ResourceLoaderTemplateSource(new DefaultResourceLoader(), properties);
        engine = new NotificationTemplateEngine(properties, source, store);
        service = new TemplateAdminService(engine, source, store, new TemplateAdminService.Options(
                TemplateAdminService.DEFAULT_EDITABLE_CHANNELS, 4096, 50),
                key -> key.equals(ACME_WELCOME) ? Optional.of(Map.of("seeded", true)) : Optional.empty(),
                changed::add);
    }

    private void write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private TemplateVersion publish(TemplateKey key, String content) {
        long revision = store.findDraft(key).map(TemplateVersion::revision).orElse(0L);
        TemplateVersion draft = service.saveDraft(key, content, MODEL, revision, "ann", null);
        return service.publish(key, draft.revision(), "ann", "why");
    }

    private String send(String tenant) {
        return engine.render(NotificationRequest.builder().tenantId(tenant).channel(Channel.EMAIL)
                .templateId("WELCOME").templateData(MODEL).build()).subject();
    }

    @Test
    void listShowsBuiltInsAndStoreEntriesFromTheDefaultLayer() {
        publish(DEFAULT_WELCOME, WELCOME.replace("Welcome", "Hello"));
        publish(ACME_WELCOME, WELCOME);
        service.saveDraft(TemplateKey.of("initech", "email", "WELCOME"), WELCOME, MODEL, 0, "ann", null);
        TemplateVersion newDraft = service.saveDraft(TemplateKey.of(null, "email", "NEW"), WELCOME, MODEL, 0, "ann",
                null);

        List<TemplateListItem> email = service.list("EMAIL");

        assertThat(email).extracting(TemplateListItem::templateId).containsExactly("NEW", "WELCOME");
        TemplateListItem fresh = email.get(0);
        assertThat(fresh.builtIn()).isFalse();
        assertThat(fresh.defaultOverride()).isNull();
        assertThat(fresh.defaultDraft().revision()).isEqualTo(newDraft.revision());
        TemplateListItem welcome = email.get(1);
        assertThat(welcome.builtIn()).isTrue();
        assertThat(welcome.builtInTenants()).containsExactly("globex");
        assertThat(welcome.defaultOverride().version()).isEqualTo(1);
        assertThat(welcome.defaultOverride().content()).isNull();
        assertThat(welcome.tenantOverrideCount()).isEqualTo(1);
        assertThat(welcome.tenantDraftCount()).isEqualTo(1);
        assertThat(welcome.editable()).isTrue();
        assertThat(service.list(null)).extracting(TemplateListItem::channel).containsExactly("email", "email", "sms");

        assertThat(service.tenantOverrides("email", null)).extracting(s -> s.key().tenantId())
                .containsExactly("acme", "initech");
        assertThat(service.tenantOverrides(null, "OTP")).isEmpty();
    }

    @Test
    void getDescribesTheLayers() {
        TemplateDetail builtIn = service.get(DEFAULT_WELCOME);
        assertThat(builtIn.effectiveSource()).isEqualTo(TemplateLayerSource.DEFAULT_BUILT_IN);
        assertThat(builtIn.builtInContent()).isEqualTo(WELCOME.stripTrailing());
        assertThat(builtIn.inheritedSource()).isEqualTo(TemplateLayerSource.DEFAULT_BUILT_IN);
        assertThat(builtIn.published()).isNull();
        assertThat(builtIn.sampleModel()).isEmpty();
        assertThat(builtIn.contentType()).isEqualTo(TemplateVersion.FREEMARKER);

        TemplateDetail acme = service.get(ACME_WELCOME);
        assertThat(acme.effectiveSource()).isEqualTo(TemplateLayerSource.DEFAULT_BUILT_IN);
        assertThat(acme.builtInContent()).isNull();
        assertThat(acme.inheritedContent()).isEqualTo(WELCOME.stripTrailing());
        assertThat(acme.sampleModel()).containsEntry("seeded", true);

        publish(DEFAULT_WELCOME, "[SUBJECT]v1 ${user.name}[/SUBJECT]body");
        acme = service.get(ACME_WELCOME);
        assertThat(acme.effectiveSource()).isEqualTo(TemplateLayerSource.DEFAULT_OVERRIDE);
        assertThat(acme.effectiveVersion()).isEqualTo(1);
        assertThat(acme.inheritedSource()).isEqualTo(TemplateLayerSource.DEFAULT_OVERRIDE);

        publish(ACME_WELCOME, "[SUBJECT]acme ${user.name}[/SUBJECT]body");
        service.saveDraft(ACME_WELCOME, "[SUBJECT]draft[/SUBJECT]body", Map.of("draft", 1), 0, "bob", null);
        acme = service.get(ACME_WELCOME);
        assertThat(acme.effectiveSource()).isEqualTo(TemplateLayerSource.TENANT_OVERRIDE);
        assertThat(acme.published().content()).contains("acme");
        assertThat(acme.draft().content()).contains("draft");
        assertThat(acme.sampleModel()).containsEntry("draft", 1);

        TemplateDetail globex = service.get(TemplateKey.of("globex", "email", "WELCOME"));
        assertThat(globex.effectiveSource()).isEqualTo(TemplateLayerSource.TENANT_BUILT_IN);
        assertThat(globex.inheritedSource()).isEqualTo(TemplateLayerSource.TENANT_BUILT_IN);

        TemplateStoreException missing = catchThrowableOfType(TemplateStoreException.class,
                () -> service.get(TemplateKey.of(null, "email", "NOPE")));
        assertThat(missing.getReason()).isEqualTo(Reason.NOT_FOUND);
    }

    @Test
    void previewReturnsTheEmailPartsEvenWhenNotSendable() {
        TemplateCheckResult preview = service.preview(ACME_WELCOME,
                "[SUBJECT]Hi ${user.name}[/SUBJECT][BODY]<p>x</p>[/BODY][TEXT]x[/TEXT]", MODEL);
        assertThat(preview.ok()).isTrue();
        assertThat(preview.subject()).isEqualTo("Hi Ada");
        assertThat(preview.html()).isEqualTo("<p>x</p>");
        assertThat(preview.text()).isEqualTo("x");

        assertThat(service.preview(ACME_WELCOME, "[SUBJECT][/SUBJECT]x", MODEL).ok()).isTrue();
        assertThat(service.validate(ACME_WELCOME, "[SUBJECT][/SUBJECT]x", MODEL).problems())
                .extracting(TemplateProblem::reason).containsExactly(TemplateProblem.Reason.EMPTY_OUTPUT);
    }

    @Test
    void validateLocatesProblems() {
        TemplateProblem syntax = service.validate(DEFAULT_WELCOME, "line\n<#if>", MODEL).problems().getFirst();
        assertThat(syntax.reason()).isEqualTo(TemplateProblem.Reason.INVALID_TEMPLATE);
        assertThat(syntax.line()).isEqualTo(2);

        TemplateProblem model = service.validate(DEFAULT_WELCOME, "[SUBJECT]s[/SUBJECT]\n${user.email}", MODEL)
                .problems().getFirst();
        assertThat(model.reason()).isEqualTo(TemplateProblem.Reason.MODEL_INVALID);
        assertThat(model.variablePath()).isEqualTo("user.email");
        assertThat(model.line()).isEqualTo(2);
        assertThat(model.column()).isEqualTo(3);
        assertThat(model.templateName()).isNull();

        TemplateProblem size = service.validate(DEFAULT_WELCOME, "x".repeat(4097), MODEL).problems().getFirst();
        assertThat(size.reason()).isEqualTo(TemplateProblem.Reason.SIZE_LIMIT);
    }

    @Test
    void jsonChannelsValidateAsJsonObjects() {
        TemplateKey whatsapp = TemplateKey.of(null, "whatsapp", "ORDER");
        assertThat(service.validate(whatsapp, "{\"name\":\"order\"}", null).ok()).isTrue();
        TemplateProblem broken = service.validate(whatsapp, "{\n  \"name\": }", null).problems().getFirst();
        assertThat(broken.reason()).isEqualTo(TemplateProblem.Reason.INVALID_TEMPLATE);
        assertThat(broken.line()).isEqualTo(2);
        assertThat(service.validate(whatsapp, "[1]", null).ok()).isFalse();
    }

    @Test
    void publishValidatesWithTheStoredSampleModelAndRefusesOnProblems() {
        TemplateVersion draft = service.saveDraft(DEFAULT_WELCOME, "[SUBJECT]${user.missing}[/SUBJECT]b", MODEL, 0,
                "ann", null);

        TemplateStoreException refused = catchThrowableOfType(TemplateStoreException.class,
                () -> service.publish(DEFAULT_WELCOME, draft.revision(), "ann", "try"));

        assertThat(refused.getReason()).isEqualTo(Reason.VALIDATION_FAILED);
        assertThat(refused.getStatusCode()).isEqualTo(422);
        assertThat(refused.getProblems()).singleElement()
                .satisfies(p -> assertThat(p.variablePath()).isEqualTo("user.missing"));
        assertThat(store.findPublished(DEFAULT_WELCOME)).isEmpty();
        assertThat(changed).isEmpty();
    }

    @Test
    void publishNeedsANoteAndTheCurrentRevision() {
        service.saveDraft(DEFAULT_WELCOME, WELCOME, MODEL, 0, "ann", null);

        assertThat(catchThrowableOfType(TemplateStoreException.class,
                () -> service.publish(DEFAULT_WELCOME, 1, "ann", " ")).getReason()).isEqualTo(Reason.INVALID_REQUEST);
        TemplateStoreException stale = catchThrowableOfType(TemplateStoreException.class,
                () -> service.publish(DEFAULT_WELCOME, 5, "ann", "n"));
        assertThat(stale.getReason()).isEqualTo(Reason.REVISION_CONFLICT);
        assertThat(stale.getCurrentRevision()).isEqualTo(1);
        assertThat(catchThrowableOfType(TemplateStoreException.class,
                () -> service.publish(ACME_WELCOME, 1, "ann", "n")).getReason()).isEqualTo(Reason.NO_DRAFT);
    }

    @Test
    void publishRestoreAndResetReachTheSendPathAtOnce() {
        assertThat(send("acme")).isEqualTo("Welcome, Ada");

        publish(DEFAULT_WELCOME, WELCOME.replace("Welcome", "v1"));
        assertThat(send("acme")).isEqualTo("v1, Ada");

        publish(ACME_WELCOME, WELCOME.replace("Welcome", "acme"));
        assertThat(send("acme")).isEqualTo("acme, Ada");
        assertThat(send("other")).isEqualTo("v1, Ada");

        service.reset(ACME_WELCOME, "ann", "back to default");
        assertThat(send("acme")).isEqualTo("v1, Ada");

        publish(DEFAULT_WELCOME, WELCOME.replace("Welcome", "v2"));
        service.restore(DEFAULT_WELCOME, 1, "ann", "undo");
        assertThat(send("acme")).isEqualTo("v1, Ada");

        service.reset(DEFAULT_WELCOME, "ann", "built-in");
        assertThat(send("acme")).isEqualTo("Welcome, Ada");

        assertThat(changed).containsExactly(DEFAULT_WELCOME, ACME_WELCOME, ACME_WELCOME, DEFAULT_WELCOME,
                DEFAULT_WELCOME, DEFAULT_WELCOME);
        assertThat(service.versions(DEFAULT_WELCOME)).extracting(TemplateVersion::version).containsExactly(3, 2, 1);
        assertThat(service.events(DEFAULT_WELCOME)).hasSize(6);
    }

    @Test
    void publishRefusesADraftReplacedSinceItWasRead() {
        TemplateVersion validated = service.saveDraft(DEFAULT_WELCOME, WELCOME, MODEL, 0, "ann", null);
        service.discard(DEFAULT_WELCOME, "bob", "start over");
        TemplateVersion replacement = service.saveDraft(DEFAULT_WELCOME, "[SUBJECT]${nope}[/SUBJECT]b", MODEL, 0,
                "bob", null);

        assertThat(replacement.revision()).isNotEqualTo(validated.revision());
        TemplateStoreException stale = catchThrowableOfType(TemplateStoreException.class,
                () -> service.publish(DEFAULT_WELCOME, validated.revision(), "ann", "n"));
        assertThat(stale.getReason()).isEqualTo(Reason.REVISION_CONFLICT);
        assertThat(store.findPublished(DEFAULT_WELCOME)).isEmpty();
    }

    @Test
    void previewAcceptsWhatTheSendPathAcceptsUnderAutoEscape() {
        properties.getTemplate().setAutoEscape(true);
        TemplateCheckResult preview = service.validate(DEFAULT_WELCOME,
                "[SUBJECT]s[/SUBJECT][BODY]<p>${html?no_esc}</p>[/BODY]", Map.of("html", "<b>x</b>"));

        assertThat(preview.problems()).isEmpty();
        assertThat(preview.html()).isEqualTo("<p><b>x</b></p>");
    }

    @Test
    void pushTemplatesArePreviewedAndValidatedThroughThePushRenderer() {
        ResourceLoaderTemplateSource source = new ResourceLoaderTemplateSource(new DefaultResourceLoader(), properties);
        TemplateAdminService withPush = new TemplateAdminService(engine, source, store,
                new TemplateAdminService.Options(TemplateAdminService.DEFAULT_EDITABLE_CHANNELS, 4096, 50,
                        List.of(new PushContentRenderer(engine))),
                TemplateSampleProvider.NONE, changed::add);
        TemplateKey push = TemplateKey.of("acme", "push", "SHIPPED");
        String template = "[TITLE]Order ${id} shipped[/TITLE][BODY]On its way[/BODY][DATA key=\"orderId\"]${id}[/DATA]";

        TemplateCheckResult preview = withPush.preview(push, template, Map.of("id", "42"));
        assertThat(preview.ok()).isTrue();
        assertThat(preview.subject()).isEqualTo("Order 42 shipped");
        assertThat(preview.text()).isEqualTo("On its way");
        assertThat(preview.payload()).isEqualTo(new PushContent("Order 42 shipped", "On its way",
                Map.of("orderId", "42")));

        TemplateCheckResult broken = withPush.validate(push, "[TITLE]never closed", Map.of());
        assertThat(broken.problems()).extracting(TemplateProblem::reason)
                .containsExactly(TemplateProblem.Reason.INVALID_TEMPLATE);

        TemplateVersion draft = withPush.saveDraft(push, "[TITLE]x[/BODY]", Map.of(), 0, "ann", null);
        assertThat(catchThrowableOfType(TemplateStoreException.class,
                () -> withPush.publish(push, draft.revision(), "ann", "n")).getReason())
                .isEqualTo(Reason.VALIDATION_FAILED);

        TemplateVersion good = withPush.saveDraft(push, template, Map.of("id", "7"), draft.revision(), "ann", null);
        withPush.publish(push, good.revision(), "ann", "n");
        assertThat(new PushContentRenderer(engine).render(NotificationRequest.builder().tenantId("acme")
                .channel(Channel.PUSH).templateId("SHIPPED").templateData(Map.of("id", "9")).build()).subject())
                .as("the send path renders the published push override").isEqualTo("Order 9 shipped");
    }

    @Test
    void readOnlyChannelsRefuseWrites() {
        TemplateKey whatsapp = TemplateKey.of(null, "whatsapp", "ORDER");
        assertThat(service.isEditable("whatsapp")).isFalse();

        for (Runnable write : List.<Runnable>of(
                () -> service.saveDraft(whatsapp, "{}", null, 0, "ann", null),
                () -> service.publish(whatsapp, 1, "ann", "n"),
                () -> service.restore(whatsapp, 1, "ann", "n"),
                () -> service.discard(whatsapp, "ann", "n"),
                () -> service.reset(whatsapp, "ann", "n"))) {
            TemplateStoreException e = catchThrowableOfType(TemplateStoreException.class, write::run);
            assertThat(e.getReason()).isEqualTo(Reason.READ_ONLY_CHANNEL);
            assertThat(e.getStatusCode()).isEqualTo(403);
        }
    }

    @Test
    void discardKeepsThePublishedVersion() {
        publish(DEFAULT_WELCOME, WELCOME);
        service.saveDraft(DEFAULT_WELCOME, "draft", null, 0, "ann", null);

        service.discard(DEFAULT_WELCOME, "ann", null);

        assertThat(store.findDraft(DEFAULT_WELCOME)).isEmpty();
        assertThat(store.findPublished(DEFAULT_WELCOME)).isPresent();
        List<TemplateSummary> summaries = store.summaries(com.lazydevs.notification.api.template.TemplateFilter.all());
        assertThat(summaries).singleElement().satisfies(s -> assertThat(s.draft()).isNull());
    }

    @Test
    void optionsNormaliseChannels() {
        TemplateAdminService.Options options = new TemplateAdminService.Options(Set.of("EMAIL"), 10, 1);
        assertThat(options.editableChannels()).containsExactly("email");
        assertThat(TemplateAdminService.Options.defaults().maxContentBytes()).isEqualTo(256 * 1024);
        assertThat(TemplateAdminService.contentTypeOf("WhatsApp")).isEqualTo(TemplateVersion.JSON);
    }
}
