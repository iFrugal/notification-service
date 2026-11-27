package com.lazydevs.notification.api.template;

import java.util.List;
import java.util.Optional;

/**
 * Editable template overrides with drafts, versions and an audit trail (DD-38).
 *
 * <p>The store holds overrides only: the engine consults it before the {@link TemplateSource}
 * at each tenant level (tenant, then default), and only its published versions are ever
 * rendered by the send path. Every key has at most one draft and at most one published
 * version. Drafts are guarded by an optimistic revision that grows with every save and is
 * never reused for a key, so a revision names exactly one draft content; every write appends
 * a {@link TemplateEvent}.
 *
 * <p>The store does not validate templates and does not authenticate: the actor is a plain
 * string the host passes, and {@code TemplateAdminService} validates before it publishes.
 * Implementations enforce the content size limit and must be thread-safe; writes to one key
 * are serialised.
 *
 * @since 1.3.0
 */
public interface TemplateStore {

    /** The published version of exactly this key. */
    Optional<TemplateVersion> findPublished(TemplateKey key);

    /** The draft of exactly this key. */
    Optional<TemplateVersion> findDraft(TemplateKey key);

    /** Published, superseded and archived versions, newest first; never the draft. */
    List<TemplateVersion> versions(TemplateKey key);

    /**
     * What the store holds per key, without content, for every key that has a draft or a
     * published version and passes {@code filter}, across tenants (as far as the backing
     * database lets this session see them).
     */
    List<TemplateSummary> summaries(TemplateFilter filter);

    /**
     * Create or update the draft.
     *
     * @param expectedRevision the draft revision the caller last read, {@code 0} when there was no draft
     * @param sampleModel      sample data model as JSON object text, may be {@code null}
     * @return the saved draft
     * @throws TemplateStoreException {@code REVISION_CONFLICT} when the draft moved on,
     *                                {@code SIZE_LIMIT} when the content or the model is too large
     */
    TemplateVersion saveDraft(TemplateKey key, String contentType, String content, String sampleModel,
                              long expectedRevision, String actor, String note);

    /**
     * Publish the draft as the next version; the previous published version becomes
     * {@code SUPERSEDED}.
     *
     * @throws TemplateStoreException {@code NO_DRAFT}, or {@code REVISION_CONFLICT} when the draft
     *                                is not at {@code draftRevision}
     */
    TemplateVersion publish(TemplateKey key, long draftRevision, String actor, String note);

    /**
     * Publish a copy of an earlier version as the next version.
     *
     * @throws TemplateStoreException {@code UNKNOWN_VERSION}
     */
    TemplateVersion restore(TemplateKey key, int version, String actor, String note);

    /**
     * Delete the draft.
     *
     * @throws TemplateStoreException {@code NO_DRAFT}
     */
    void discardDraft(TemplateKey key, String actor, String note);

    /**
     * Archive the published version, so the key falls back to the next layer ("reset to
     * built-in"). The draft, if any, is kept.
     *
     * @throws TemplateStoreException {@code NO_OVERRIDE}
     */
    void archive(TemplateKey key, String actor, String note);

    /**
     * Record the provider's approval of a version.
     *
     * @throws TemplateStoreException {@code UNKNOWN_VERSION}
     */
    TemplateVersion updateApproval(TemplateKey key, int version, TemplateApprovalStatus status,
                                   String providerRef, String providerReason, String actor);

    /** The key's audit trail, newest first, at most {@code limit} entries. */
    List<TemplateEvent> events(TemplateKey key, int limit);
}
