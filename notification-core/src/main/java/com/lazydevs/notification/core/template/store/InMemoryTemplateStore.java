package com.lazydevs.notification.core.template.store;

import com.lazydevs.notification.api.template.TemplateApprovalStatus;
import com.lazydevs.notification.api.template.TemplateEvent;
import com.lazydevs.notification.api.template.TemplateEventAction;
import com.lazydevs.notification.api.template.TemplateFilter;
import com.lazydevs.notification.api.template.TemplateKey;
import com.lazydevs.notification.api.template.TemplateStore;
import com.lazydevs.notification.api.template.TemplateStoreChecks;
import com.lazydevs.notification.api.template.TemplateStoreException;
import com.lazydevs.notification.api.template.TemplateSummary;
import com.lazydevs.notification.api.template.TemplateVersion;
import com.lazydevs.notification.api.template.TemplateVersionStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link TemplateStore} in this process's memory (DD-38), for tests, single-instance
 * deployments and trying the admin API out ({@code notification.template.store.type=memory}).
 * Everything is lost on restart, and other replicas do not see it.
 * <p>
 * All operations are serialised on the store, which is ample for an admin workload.
 */
public class InMemoryTemplateStore implements TemplateStore {

    private final int maxContentBytes;
    private final Clock clock;

    /** Published, superseded and archived versions per key, oldest first. */
    private final Map<TemplateKey, List<TemplateVersion>> versions = new HashMap<>();
    private final Map<TemplateKey, TemplateVersion> drafts = new HashMap<>();
    private final List<TemplateEvent> events = new ArrayList<>();
    private long lastEventId;

    public InMemoryTemplateStore(int maxContentBytes) {
        this(maxContentBytes, Clock.systemUTC());
    }

    public InMemoryTemplateStore(int maxContentBytes, Clock clock) {
        this.maxContentBytes = TemplateStoreChecks.requirePositiveLimit(maxContentBytes);
        this.clock = clock;
    }

    @Override
    public synchronized Optional<TemplateVersion> findPublished(TemplateKey key) {
        return history(key).stream().filter(v -> v.status() == TemplateVersionStatus.PUBLISHED).findFirst();
    }

    @Override
    public synchronized Optional<TemplateVersion> findDraft(TemplateKey key) {
        return Optional.ofNullable(drafts.get(key));
    }

    @Override
    public synchronized List<TemplateVersion> versions(TemplateKey key) {
        List<TemplateVersion> newestFirst = new ArrayList<>(history(key));
        newestFirst.sort(Comparator.comparingInt(TemplateVersion::version).reversed());
        return newestFirst;
    }

    @Override
    public synchronized List<TemplateSummary> summaries(TemplateFilter filter) {
        Set<TemplateKey> keys = new LinkedHashSet<>(versions.keySet());
        keys.addAll(drafts.keySet());
        List<TemplateSummary> result = new ArrayList<>();
        for (TemplateKey key : keys) {
            if (!filter.matches(key)) {
                continue;
            }
            TemplateVersion published = findPublished(key).map(TemplateVersion::withoutContent).orElse(null);
            TemplateVersion draft = drafts.containsKey(key) ? drafts.get(key).withoutContent() : null;
            if (published != null || draft != null) {
                result.add(new TemplateSummary(key, published, draft));
            }
        }
        result.sort(Comparator.comparing((TemplateSummary s) -> s.key().name()));
        return result;
    }

    @Override
    public synchronized TemplateVersion saveDraft(TemplateKey key, String contentType, String content,
                                                  String sampleModel, long expectedRevision, String actor,
                                                  String note) {
        TemplateStoreChecks.requireActor(key, actor);
        TemplateStoreChecks.requireDraft(key, contentType, content, sampleModel, maxContentBytes);
        TemplateVersion current = drafts.get(key);
        long currentRevision = current == null ? 0 : current.revision();
        if (expectedRevision != currentRevision) {
            throw TemplateStoreException.revisionConflict(key, expectedRevision, currentRevision);
        }
        Instant now = clock.instant();
        // The save's event id is the new revision: increasing, and never reused for the key
        long revision = event(key, current == null ? TemplateEventAction.CREATED_DRAFT
                : TemplateEventAction.UPDATED_DRAFT, null, actor, note, now);
        TemplateVersion draft = current == null
                ? TemplateVersion.builder().key(key).version(0).status(TemplateVersionStatus.DRAFT)
                .createdBy(actor).createdAt(now).build()
                : current;
        draft = draft.toBuilder().contentType(contentType).content(content).sampleModel(sampleModel)
                .revision(revision).updatedBy(actor).updatedAt(now).note(note).build();
        drafts.put(key, draft);
        return draft;
    }

    @Override
    public synchronized TemplateVersion publish(TemplateKey key, long draftRevision, String actor, String note) {
        TemplateStoreChecks.requireActor(key, actor);
        TemplateVersion draft = drafts.get(key);
        if (draft == null) {
            throw new TemplateStoreException(TemplateStoreException.Reason.NO_DRAFT, key,
                    "Template " + key.name() + " has no draft");
        }
        if (draft.revision() != draftRevision) {
            throw TemplateStoreException.revisionConflict(key, draftRevision, draft.revision());
        }
        Instant now = clock.instant();
        TemplateVersion published = draft.toBuilder().version(nextVersion(key)).status(TemplateVersionStatus.PUBLISHED)
                .publishedBy(actor).publishedAt(now).note(note).build();
        supersede(key);
        drafts.remove(key);
        versions.computeIfAbsent(key, k -> new ArrayList<>()).add(published);
        event(key, TemplateEventAction.PUBLISHED, published.version(), actor, note, now);
        return published;
    }

    @Override
    public synchronized TemplateVersion restore(TemplateKey key, int version, String actor, String note) {
        TemplateStoreChecks.requireActor(key, actor);
        TemplateVersion source = history(key).stream().filter(v -> v.version() == version).findFirst()
                .orElseThrow(() -> unknownVersion(key, version));
        Instant now = clock.instant();
        TemplateVersion restored = source.toBuilder().version(nextVersion(key)).status(TemplateVersionStatus.PUBLISHED)
                .createdBy(actor).createdAt(now).updatedBy(actor).updatedAt(now)
                .publishedBy(actor).publishedAt(now).note(note).build();
        supersede(key);
        versions.computeIfAbsent(key, k -> new ArrayList<>()).add(restored);
        event(key, TemplateEventAction.RESTORED, restored.version(), actor, note, now);
        return restored;
    }

    @Override
    public synchronized void discardDraft(TemplateKey key, String actor, String note) {
        TemplateStoreChecks.requireActor(key, actor);
        if (drafts.remove(key) == null) {
            throw new TemplateStoreException(TemplateStoreException.Reason.NO_DRAFT, key,
                    "Template " + key.name() + " has no draft");
        }
        event(key, TemplateEventAction.DISCARDED, null, actor, note, clock.instant());
    }

    @Override
    public synchronized void archive(TemplateKey key, String actor, String note) {
        TemplateStoreChecks.requireActor(key, actor);
        TemplateVersion published = findPublished(key).orElseThrow(() -> new TemplateStoreException(
                TemplateStoreException.Reason.NO_OVERRIDE, key,
                "Template " + key.name() + " has no published override"));
        replace(key, published, published.toBuilder().status(TemplateVersionStatus.ARCHIVED).build());
        event(key, TemplateEventAction.RESET, published.version(), actor, note, clock.instant());
    }

    @Override
    public synchronized TemplateVersion updateApproval(TemplateKey key, int version, TemplateApprovalStatus status,
                                                       String providerRef, String providerReason, String actor) {
        TemplateStoreChecks.requireActor(key, actor);
        TemplateVersion current = history(key).stream().filter(v -> v.version() == version).findFirst()
                .orElseThrow(() -> unknownVersion(key, version));
        TemplateVersion updated = current.toBuilder().approvalStatus(status).providerRef(providerRef)
                .providerReason(providerReason).build();
        replace(key, current, updated);
        event(key, TemplateEventAction.PROVIDER_STATUS, version, actor, status == null ? null : status.name(),
                clock.instant());
        return updated;
    }

    @Override
    public synchronized List<TemplateEvent> events(TemplateKey key, int limit) {
        List<TemplateEvent> result = new ArrayList<>();
        for (int i = events.size() - 1; i >= 0 && result.size() < limit; i--) {
            if (events.get(i).key().equals(key)) {
                result.add(events.get(i));
            }
        }
        return result;
    }

    private List<TemplateVersion> history(TemplateKey key) {
        return versions.getOrDefault(key, List.of());
    }

    private int nextVersion(TemplateKey key) {
        return history(key).stream().mapToInt(TemplateVersion::version).max().orElse(0) + 1;
    }

    private void supersede(TemplateKey key) {
        findPublished(key).ifPresent(current ->
                replace(key, current, current.toBuilder().status(TemplateVersionStatus.SUPERSEDED).build()));
    }

    private void replace(TemplateKey key, TemplateVersion current, TemplateVersion updated) {
        List<TemplateVersion> list = versions.get(key);
        list.set(list.indexOf(current), updated);
    }

    private long event(TemplateKey key, TemplateEventAction action, Integer version, String actor, String note,
                       Instant at) {
        events.add(new TemplateEvent(++lastEventId, key, action, version, actor, at, note));
        return lastEventId;
    }

    private static TemplateStoreException unknownVersion(TemplateKey key, int version) {
        return new TemplateStoreException(TemplateStoreException.Reason.UNKNOWN_VERSION, key,
                "Template " + key.name() + " has no version " + version);
    }
}
