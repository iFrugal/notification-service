package com.lazydevs.notification.rest.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.template.TemplateKey;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import com.lazydevs.notification.core.template.ResourceLoaderTemplateSource;
import com.lazydevs.notification.core.template.admin.TemplateAdminService;
import com.lazydevs.notification.core.template.admin.TemplateSampleProvider;
import com.lazydevs.notification.core.template.store.InMemoryTemplateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DD-38: the template admin REST API over a real admin service and in-memory store.
 */
class TemplateAdminControllerTest {

    private static final String BASE = "/api/v1/admin/templates";
    private static final String WELCOME = "[SUBJECT]Hi ${user.name}[/SUBJECT][BODY]<p>${user.name}</p>[/BODY]";

    @TempDir
    Path dir;

    private final ObjectMapper json = new ObjectMapper();
    private final List<TemplateKey> changed = new ArrayList<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        Path file = dir.resolve("default/email/WELCOME.ftl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, WELCOME);
        NotificationProperties properties = new NotificationProperties();
        properties.getTemplate().setBasePath(dir.toUri().toString());
        properties.getTemplate().setLegacyHelpers(false);
        InMemoryTemplateStore store = new InMemoryTemplateStore(1024);
        ResourceLoaderTemplateSource source = new ResourceLoaderTemplateSource(new DefaultResourceLoader(), properties);
        NotificationTemplateEngine engine = new NotificationTemplateEngine(properties, source, store);
        TemplateAdminService service = new TemplateAdminService(engine, source, store,
                TemplateAdminService.Options.defaults(), TemplateSampleProvider.NONE, changed::add);
        ActorResolver headerActor = request -> Optional.ofNullable(request.getHeader("X-Actor"));
        mvc = MockMvcBuilders.standaloneSetup(new TemplateAdminController(service, headerActor)).build();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("X-Actor", "ann");
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private Map<String, Object> draft(String content, long expectedRevision) {
        return Map.of("content", content, "sampleModel", Map.of("user", Map.of("name", "Ada")),
                "expectedRevision", expectedRevision, "note", "edit");
    }

    @Test
    void listAndGetShowTheBuiltIn() throws Exception {
        call(get(BASE).param("channel", "email"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates[0].channel").value("email"))
                .andExpect(jsonPath("$.templates[0].templateId").value("WELCOME"))
                .andExpect(jsonPath("$.templates[0].builtIn").value(true))
                .andExpect(jsonPath("$.templates[0].editable").value(true));

        call(get(BASE + "/email/WELCOME").param("tenantId", "acme"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key.tenantId").value("acme"))
                .andExpect(jsonPath("$.key.defaultLayer").doesNotExist())
                .andExpect(jsonPath("$.effectiveSource").value("DEFAULT_BUILT_IN"))
                .andExpect(jsonPath("$.inheritedContent").value(WELCOME))
                .andExpect(jsonPath("$.published").value(nullValue()));

        call(get(BASE + "/email/NOPE"), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void previewAndValidateAnswer200WithProblems() throws Exception {
        call(post(BASE + "/email/WELCOME/preview"), Map.of("content", WELCOME,
                "model", Map.of("user", Map.of("name", "Ada"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.subject").value("Hi Ada"))
                .andExpect(jsonPath("$.html").value("<p>Ada</p>"));

        call(post(BASE + "/email/WELCOME/validate"), Map.of("content", "[SUBJECT]x[/SUBJECT]\n${user.email}",
                "model", Map.of("user", Map.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.problems[0].reason").value("MODEL_INVALID"))
                .andExpect(jsonPath("$.problems[0].line").value(2))
                .andExpect(jsonPath("$.problems[0].column").value(3))
                .andExpect(jsonPath("$.problems[0].variablePath").value("user.email"));
    }

    @Test
    void draftPublishVersionsEventsRestoreReset() throws Exception {
        call(put(BASE + "/email/WELCOME/draft"), draft(WELCOME.replace("Hi", "Hello"), 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.createdBy").value("ann"));

        call(put(BASE + "/email/WELCOME/draft"), draft("x", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REVISION_CONFLICT"))
                .andExpect(jsonPath("$.currentRevision").value(1));

        call(post(BASE + "/email/WELCOME/publish"), Map.of("draftRevision", 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        call(post(BASE + "/email/WELCOME/publish"), Map.of("draftRevision", 1, "note", "friendlier"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedBy").value("ann"));
        assertThat(changed).containsExactly(TemplateKey.of(null, "email", "WELCOME"));

        call(get(BASE + "/email/WELCOME"), null)
                .andExpect(jsonPath("$.effectiveSource").value("DEFAULT_OVERRIDE"))
                .andExpect(jsonPath("$.sampleModel.user.name").value("Ada"));

        call(post(BASE + "/email/WELCOME/versions/1/restore"), Map.of("note", "again"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        call(post(BASE + "/email/WELCOME/versions/9/restore"), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("UNKNOWN_VERSION"));

        call(get(BASE + "/email/WELCOME/versions"), null)
                .andExpect(jsonPath("$.versions[0].version").value(2))
                .andExpect(jsonPath("$.versions[1].status").value("SUPERSEDED"));

        call(post(BASE + "/email/WELCOME/reset"), Map.of("note", "built-in"))
                .andExpect(status().isNoContent());
        call(post(BASE + "/email/WELCOME/reset"), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NO_OVERRIDE"));

        call(get(BASE + "/email/WELCOME/events"), null)
                .andExpect(jsonPath("$.events[0].action").value("RESET"))
                .andExpect(jsonPath("$.events[0].actor").value("ann"))
                .andExpect(jsonPath("$.events.length()").value(4));
    }

    @Test
    void publishRefusesAnInvalidDraftWith422() throws Exception {
        call(put(BASE + "/email/WELCOME/draft"), Map.of("content", "[SUBJECT]${nope}[/SUBJECT]b",
                "expectedRevision", 0)).andExpect(status().isOk());

        call(post(BASE + "/email/WELCOME/publish"), Map.of("draftRevision", 1, "note", "n"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].reason").value("MODEL_INVALID"))
                .andExpect(jsonPath("$.errors[0].variablePath").value("nope"));
    }

    @Test
    void tenantLayerDiscardAndOverrideListing() throws Exception {
        call(put(BASE + "/email/WELCOME/draft").param("tenantId", "acme"), draft(WELCOME, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key.tenantId").value("acme"));
        call(get(BASE + "/tenant-overrides").param("channel", "email"), null)
                .andExpect(jsonPath("$.overrides[0].key.tenantId").value("acme"))
                .andExpect(jsonPath("$.overrides[0].draft.revision").value(1))
                .andExpect(jsonPath("$.overrides[0].draft.content").value(nullValue()));

        call(delete(BASE + "/email/WELCOME/draft").param("tenantId", "acme").param("note", "no"), null)
                .andExpect(status().isNoContent());
        call(delete(BASE + "/email/WELCOME/draft").param("tenantId", "acme"), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NO_DRAFT"));
    }

    @Test
    void refusals() throws Exception {
        mvc.perform(put(BASE + "/email/WELCOME/draft").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(draft(WELCOME, 0))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));

        call(put(BASE + "/whatsapp/ORDER/draft"), draft("{}", 0))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("READ_ONLY_CHANNEL"));

        call(put(BASE + "/email/WELCOME/draft"), draft("x".repeat(2000), 0))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error").value("SIZE_LIMIT"))
                .andExpect(jsonPath("$.errors[0].reason").value("SIZE_LIMIT"));

        call(get(BASE + "/email/..x"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        call(put(BASE + "/email/WELCOME/draft").contentType(MediaType.APPLICATION_JSON).content("{"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void principalResolverReadsTheUserPrincipal() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(ActorResolver.principal().resolve(request)).isEmpty();
        request.setUserPrincipal(() -> "staff-1");
        assertThat(ActorResolver.principal().resolve(request)).contains("staff-1");
    }
}
