package com.lazydevs.notification.core.template.admin;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.exception.TemplateRenderException;
import com.lazydevs.notification.api.template.TemplateChangeListener;
import com.lazydevs.notification.api.template.TemplateDocument;
import com.lazydevs.notification.api.template.TemplateEvent;
import com.lazydevs.notification.api.template.TemplateFilter;
import com.lazydevs.notification.api.template.TemplateKey;
import com.lazydevs.notification.api.template.TemplateProblem;
import com.lazydevs.notification.api.template.TemplateSource;
import com.lazydevs.notification.api.template.TemplateStore;
import com.lazydevs.notification.api.template.TemplateStoreChecks;
import com.lazydevs.notification.api.template.TemplateStoreException;
import com.lazydevs.notification.api.template.TemplateStoreException.Reason;
import com.lazydevs.notification.api.template.TemplateSummary;
import com.lazydevs.notification.api.template.TemplateVersion;
import com.lazydevs.notification.core.template.ChannelContentRenderer;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import freemarker.template.TemplateException;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Browse, preview, edit, publish and roll back templates (DD-38). Framework-free: a host
 * calls it from its own controller, or enables the REST module's
 * {@code TemplateAdminController}.
 * <p>
 * The store holds overrides of the built-in templates. Publishing is refused unless the draft
 * parses and renders with its stored sample model (an email must also have a subject and a
 * body), and every write names an actor and is kept in the store's audit trail. A publish,
 * restore or reset evicts the engine's cached copies and tells the local
 * {@link TemplateChangeListener}s at once; other replicas learn of it through the
 * {@code TemplateChangeFeed}.
 * <p>
 * The service does not authenticate or authorise: the host decides who may call what, and
 * passes the actor's name.
 *
 * @since 1.3.0
 */
public class TemplateAdminService {

    /** Channels editable by default: the FreeMarker ones. WhatsApp bindings are read-only (DD-38). */
    public static final Set<String> DEFAULT_EDITABLE_CHANNELS = Set.of("email", "sms", "push");

    /** Most events {@link #events} returns. */
    public static final int DEFAULT_EVENT_LIMIT = 500;

    private static final TypeReference<Map<String, Object>> MODEL = new TypeReference<>() {
    };

    private final NotificationTemplateEngine engine;
    private final TemplateSource builtIn;
    private final TemplateStore store;
    private final Options options;
    private final TemplateSampleProvider samples;
    private final TemplateChangeListener changes;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Settings of the service.
     *
     * @param editableChannels channels whose templates may be written, lower case
     * @param maxContentBytes  limit for content and sample model, in bytes of UTF-8 (as the store's)
     * @param eventLimit       most events {@link #events} returns
     * @param renderers        the send path's channel renderers, such as the push renderer with
     *                         its {@code [TITLE]} and {@code [DATA]} sections; a FreeMarker
     *                         template of a channel with one is previewed and validated through it
     */
    public record Options(Set<String> editableChannels, int maxContentBytes, int eventLimit,
                          List<ChannelContentRenderer> renderers) {

        public Options {
            editableChannels = editableChannels == null ? Set.of()
                    : editableChannels.stream().map(c -> c.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
            TemplateStoreChecks.requirePositiveLimit(maxContentBytes);
            if (eventLimit < 1) {
                throw new IllegalArgumentException("eventLimit must be positive, got " + eventLimit);
            }
            renderers = renderers == null ? List.of() : List.copyOf(renderers);
        }

        /** Without channel renderers. */
        public Options(Set<String> editableChannels, int maxContentBytes, int eventLimit) {
            this(editableChannels, maxContentBytes, eventLimit, List.of());
        }

        /** The renderer for {@code channel}, or {@code null}. */
        ChannelContentRenderer renderer(Channel channel) {
            return renderers.stream().filter(r -> r.channel() == channel).findFirst().orElse(null);
        }

        /** The defaults: email, SMS and push editable, 256 KiB, 500 events. */
        public static Options defaults() {
            return new Options(DEFAULT_EDITABLE_CHANNELS, TemplateStoreChecks.DEFAULT_MAX_CONTENT_BYTES,
                    DEFAULT_EVENT_LIMIT);
        }
    }

    /**
     * @param engine  renders previews and validates before publishing; should be the engine that
     *                reads {@code store}, so it serves the same includes
     * @param builtIn the engine's {@link TemplateSource}, for built-in content and the listing
     * @param store   the template store
     * @param samples seeds sample models; {@link TemplateSampleProvider#NONE} for none
     * @param changes told about each publish, restore and reset in this process, after the
     *                engine's own copies are evicted
     */
    public TemplateAdminService(NotificationTemplateEngine engine, TemplateSource builtIn, TemplateStore store,
                                Options options, TemplateSampleProvider samples, TemplateChangeListener changes) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.builtIn = Objects.requireNonNull(builtIn, "builtIn");
        this.store = Objects.requireNonNull(store, "store");
        this.options = Objects.requireNonNull(options, "options");
        this.samples = samples == null ? TemplateSampleProvider.NONE : samples;
        this.changes = changes == null ? key -> { } : changes;
    }

    private static Channel channelOf(String channel) {
        for (Channel candidate : Channel.values()) {
            if (candidate.name().equalsIgnoreCase(channel)) {
                return candidate;
            }
        }
        return null;
    }

    /** The content type templates of {@code channel} are stored as. */
    public static String contentTypeOf(String channel) {
        return Channel.WHATSAPP.name().equalsIgnoreCase(channel) ? TemplateVersion.JSON : TemplateVersion.FREEMARKER;
    }

    /** Whether templates of {@code channel} may be written. */
    public boolean isEditable(String channel) {
        return channel != null && options.editableChannels().contains(channel.toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------- reads

    /**
     * Every template known as a built-in file or a store entry, seen from the default layer,
     * sorted by channel and id.
     *
     * @param channel only this channel; {@code null} for all
     */
    public List<TemplateListItem> list(String channel) {
        String wanted = blankToNull(channel);
        Map<String, ListEntry> entries = new TreeMap<>();
        builtIn.list().stream()
                .filter(key -> key.locale().isEmpty() && (wanted == null || wanted.equals(key.channel())))
                .forEach(key -> entry(entries, key).addBuiltIn(key));
        store.summaries(new TemplateFilter(wanted, null)).stream()
                .filter(summary -> summary.key().locale().isEmpty())
                .forEach(summary -> entry(entries, summary.key()).addStored(summary));
        return entries.values().stream().map(e -> e.toItem(isEditable(e.channel))).toList();
    }

    private static ListEntry entry(Map<String, ListEntry> entries, TemplateKey key) {
        return entries.computeIfAbsent(key.channel() + "/" + key.templateId(),
                k -> new ListEntry(key.channel(), key.templateId()));
    }

    private static String blankToNull(String channel) {
        return channel == null || channel.isBlank() ? null : channel.toLowerCase(Locale.ROOT);
    }

    /**
     * Tenant-layer store entries, across tenants as far as the store lets this session see them.
     *
     * @param channel    only this channel; {@code null} for all
     * @param templateId only this template; {@code null} for all
     */
    public List<TemplateSummary> tenantOverrides(String channel, String templateId) {
        String wantedChannel = blankToNull(channel);
        String wantedId = templateId == null || templateId.isBlank() ? null : templateId;
        return store.summaries(new TemplateFilter(wantedChannel, wantedId)).stream()
                .filter(summary -> !summary.key().isDefaultLayer())
                .sorted(Comparator.comparing((TemplateSummary s) -> s.key().tenantId())
                        .thenComparing(s -> s.key().name()))
                .toList();
    }

    /**
     * One template in one layer, with its layering.
     *
     * @throws TemplateStoreException {@code NOT_FOUND} when the template exists in no layer
     */
    public TemplateDetail get(TemplateKey key) {
        Optional<TemplateVersion> published = store.findPublished(key);
        Optional<TemplateVersion> draft = store.findDraft(key);
        Optional<TemplateDocument> ownBuiltIn = builtIn.find(key);

        Layer inherited = inherited(key, ownBuiltIn);
        Layer effective = published.map(v -> new Layer(key.isDefaultLayer()
                        ? TemplateLayerSource.DEFAULT_OVERRIDE : TemplateLayerSource.TENANT_OVERRIDE,
                        v.content(), v.version()))
                .orElse(inherited);
        if (effective.source() == TemplateLayerSource.NONE && draft.isEmpty()) {
            throw new TemplateStoreException(Reason.NOT_FOUND, key, "Template " + key.name() + " exists in no layer");
        }

        Map<String, Object> sampleModel = draft.map(TemplateVersion::sampleModel)
                .or(() -> published.map(TemplateVersion::sampleModel))
                .map(this::parseModel)
                .or(() -> samples.sample(key))
                .orElseGet(LinkedHashMap::new);
        return new TemplateDetail(key, contentTypeOf(key.channel()), isEditable(key.channel()),
                effective.source(), effective.version(), ownBuiltIn.map(TemplateDocument::content).orElse(null),
                inherited.source(), inherited.content(), published.orElse(null), draft.orElse(null), sampleModel);
    }

    /** What {@code key}'s layer falls back to without its own store override. */
    private Layer inherited(TemplateKey key, Optional<TemplateDocument> ownBuiltIn) {
        if (!key.isDefaultLayer()) {
            if (ownBuiltIn.isPresent()) {
                return new Layer(TemplateLayerSource.TENANT_BUILT_IN, ownBuiltIn.get().content(), null);
            }
            TemplateKey defaults = key.defaultLayer();
            Optional<TemplateVersion> defaultOverride = store.findPublished(defaults);
            if (defaultOverride.isPresent()) {
                return new Layer(TemplateLayerSource.DEFAULT_OVERRIDE, defaultOverride.get().content(),
                        defaultOverride.get().version());
            }
            return builtIn.find(defaults)
                    .map(d -> new Layer(TemplateLayerSource.DEFAULT_BUILT_IN, d.content(), null))
                    .orElse(Layer.NONE);
        }
        return ownBuiltIn.map(d -> new Layer(TemplateLayerSource.DEFAULT_BUILT_IN, d.content(), null))
                .orElse(Layer.NONE);
    }

    /** Published, superseded and archived versions with content, newest first. */
    public List<TemplateVersion> versions(TemplateKey key) {
        return store.versions(key);
    }

    /** The audit trail, newest first. */
    public List<TemplateEvent> events(TemplateKey key) {
        return store.events(key, options.eventLimit());
    }

    // ---------------------------------------------------------------- checks

    /**
     * Render unsaved content as {@code key}'s tenant and channel would, for a preview. An email
     * with an empty subject or body still previews.
     */
    public TemplateCheckResult preview(TemplateKey key, String content, Map<String, Object> model) {
        return check(key, content, model, false);
    }

    /**
     * Whether {@code content} could be published: it fits the size limit, parses, and renders
     * with {@code model} (an email also needs a subject and a body). JSON content must be a
     * JSON object.
     */
    public TemplateCheckResult validate(TemplateKey key, String content, Map<String, Object> model) {
        return check(key, content, model, true);
    }

    private TemplateCheckResult check(TemplateKey key, String content, Map<String, Object> model, boolean strict) {
        String text = content == null ? "" : content;
        int size = text.getBytes(StandardCharsets.UTF_8).length;
        if (size > options.maxContentBytes()) {
            return TemplateCheckResult.failed(List.of(TemplateProblem.of(TemplateProblem.Reason.SIZE_LIMIT,
                    "The content is " + size + " bytes; the limit is " + options.maxContentBytes())));
        }
        if (TemplateVersion.JSON.equals(contentTypeOf(key.channel()))) {
            return checkJson(text);
        }
        Map<String, Object> data = model == null ? Map.of() : model;
        try {
            ChannelContentRenderer renderer = channelOf(key.channel()) == null ? null
                    : options.renderer(channelOf(key.channel()));
            if (renderer != null) {
                NotificationRequest request = NotificationRequest.builder()
                        .tenantId(key.tenantDirectory())
                        .channel(renderer.channel())
                        .templateId(key.templateId())
                        .templateData(data)
                        .build();
                return TemplateCheckResult.rendered(engine.renderCandidate(key, text, () -> renderer.render(request)));
            }
            return TemplateCheckResult.rendered(engine.renderSource(key, text, data, strict));
        } catch (TemplateRenderException e) {
            return TemplateCheckResult.failed(List.of(problemOf(key, e)));
        }
    }

    private TemplateCheckResult checkJson(String text) {
        try {
            JsonNode node = json.readTree(text);
            if (node == null || !node.isObject()) {
                return TemplateCheckResult.failed(List.of(TemplateProblem.of(TemplateProblem.Reason.INVALID_TEMPLATE,
                        "The content must be a JSON object")));
            }
            return new TemplateCheckResult(true, null, null, json.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(node), null, List.of());
        } catch (JsonProcessingException e) {
            JsonLocation at = e.getLocation();
            return TemplateCheckResult.failed(List.of(new TemplateProblem(TemplateProblem.Reason.INVALID_TEMPLATE,
                    e.getOriginalMessage(), at == null ? null : at.getLineNr(), at == null ? null : at.getColumnNr(),
                    null, null)));
        }
    }

    private static TemplateProblem problemOf(TemplateKey key, TemplateRenderException e) {
        String templateName = null;
        if (e.getCause() instanceof TemplateException cause && cause.getTemplateSourceName() != null
                && !key.name().equals(cause.getTemplateSourceName())) {
            templateName = cause.getTemplateSourceName();
        }
        return new TemplateProblem(TemplateProblem.Reason.valueOf(e.getReason().name()), e.getMessage(),
                e.getLineNumber(), e.getColumnNumber(), e.getVariablePath(), templateName);
    }

    // ---------------------------------------------------------------- writes

    /**
     * Create or update {@code key}'s draft.
     *
     * @param expectedRevision the draft revision the caller last read, {@code 0} when there was none
     * @throws TemplateStoreException {@code READ_ONLY_CHANNEL}, {@code REVISION_CONFLICT}, {@code SIZE_LIMIT}
     */
    public TemplateVersion saveDraft(TemplateKey key, String content, Map<String, Object> sampleModel,
                                     long expectedRevision, String actor, String note) {
        requireEditable(key);
        return store.saveDraft(key, contentTypeOf(key.channel()), content, writeModel(key, sampleModel),
                expectedRevision, actor, note);
    }

    /**
     * Publish {@code key}'s draft after validating it with its sample model.
     *
     * @param note required: why the change is made
     * @throws TemplateStoreException {@code READ_ONLY_CHANNEL}, {@code INVALID_REQUEST} without a
     *                                note, {@code NO_DRAFT}, {@code REVISION_CONFLICT}, or
     *                                {@code VALIDATION_FAILED} with the problems
     */
    public TemplateVersion publish(TemplateKey key, long draftRevision, String actor, String note) {
        requireEditable(key);
        if (note == null || note.isBlank()) {
            throw new TemplateStoreException(Reason.INVALID_REQUEST, key, "A note is required to publish");
        }
        TemplateVersion draft = store.findDraft(key).orElseThrow(() ->
                new TemplateStoreException(Reason.NO_DRAFT, key, "Template " + key.name() + " has no draft"));
        if (draft.revision() != draftRevision) {
            throw TemplateStoreException.revisionConflict(key, draftRevision, draft.revision());
        }
        Map<String, Object> model = draft.sampleModel() == null ? Map.of() : parseModel(draft.sampleModel());
        TemplateCheckResult check = validate(key, draft.content(), model);
        if (!check.ok()) {
            throw new TemplateStoreException(Reason.VALIDATION_FAILED, key,
                    "Template " + key.name() + " was not published: " + check.problems().getFirst().message(), 0,
                    check.problems());
        }
        TemplateVersion published = store.publish(key, draftRevision, actor, note);
        changed(key);
        return published;
    }

    /**
     * Publish a copy of an earlier version. Not validated again: restoring is the way back from
     * a bad publish and must not be blocked.
     *
     * @throws TemplateStoreException {@code READ_ONLY_CHANNEL}, {@code UNKNOWN_VERSION}
     */
    public TemplateVersion restore(TemplateKey key, int version, String actor, String note) {
        requireEditable(key);
        TemplateVersion restored = store.restore(key, version, actor, note);
        changed(key);
        return restored;
    }

    /**
     * Delete {@code key}'s draft.
     *
     * @throws TemplateStoreException {@code READ_ONLY_CHANNEL}, {@code NO_DRAFT}
     */
    public void discard(TemplateKey key, String actor, String note) {
        requireEditable(key);
        store.discardDraft(key, actor, note);
    }

    /**
     * Archive {@code key}'s published override, so the layer falls back to the next one
     * ("reset to built-in").
     *
     * @throws TemplateStoreException {@code READ_ONLY_CHANNEL}, {@code NO_OVERRIDE}
     */
    public void reset(TemplateKey key, String actor, String note) {
        requireEditable(key);
        store.archive(key, actor, note);
        changed(key);
    }

    /** Evict the engine's copies at once, then tell the other listeners in this process. */
    private void changed(TemplateKey key) {
        engine.evict(key);
        changes.templateChanged(key);
    }

    private void requireEditable(TemplateKey key) {
        if (!isEditable(key.channel())) {
            throw new TemplateStoreException(Reason.READ_ONLY_CHANNEL, key,
                    "Templates of channel " + key.channel() + " are read-only here");
        }
    }

    private Map<String, Object> parseModel(String text) {
        try {
            Map<String, Object> model = json.readValue(text, MODEL);
            return model == null ? new LinkedHashMap<>() : model;
        } catch (JsonProcessingException _) {
            // Stored by this service as a JSON object, so only a hand-edited row lands here
            return new LinkedHashMap<>();
        }
    }

    private String writeModel(TemplateKey key, Map<String, Object> model) {
        if (model == null) {
            return null;
        }
        try {
            return json.writeValueAsString(model);
        } catch (JsonProcessingException e) {
            throw new TemplateStoreException(Reason.INVALID_REQUEST, key,
                    "The sample model cannot be written as JSON: " + e.getOriginalMessage());
        }
    }

    /** A layer's source, content and store version. */
    private record Layer(TemplateLayerSource source, String content, Integer version) {
        static final Layer NONE = new Layer(TemplateLayerSource.NONE, null, null);
    }

    /** A listing row being assembled. */
    private static final class ListEntry {
        final String channel;
        final String templateId;
        final Set<String> builtInTenants = new TreeSet<>();
        boolean builtIn;
        TemplateVersion defaultOverride;
        TemplateVersion defaultDraft;
        int tenantOverrides;
        int tenantDrafts;

        ListEntry(String channel, String templateId) {
            this.channel = channel;
            this.templateId = templateId;
        }

        void addBuiltIn(TemplateKey key) {
            if (key.isDefaultLayer()) {
                builtIn = true;
            } else {
                builtInTenants.add(key.tenantId());
            }
        }

        void addStored(TemplateSummary summary) {
            if (summary.key().isDefaultLayer()) {
                defaultOverride = summary.published();
                defaultDraft = summary.draft();
                return;
            }
            if (summary.published() != null) {
                tenantOverrides++;
            }
            if (summary.draft() != null) {
                tenantDrafts++;
            }
        }

        TemplateListItem toItem(boolean editable) {
            return new TemplateListItem(channel, templateId, builtIn, List.copyOf(builtInTenants), defaultOverride,
                    defaultDraft, tenantOverrides, tenantDrafts, editable);
        }
    }

}
