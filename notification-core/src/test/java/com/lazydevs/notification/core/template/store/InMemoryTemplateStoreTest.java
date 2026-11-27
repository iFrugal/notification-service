package com.lazydevs.notification.core.template.store;

import com.lazydevs.notification.api.template.TemplateApprovalStatus;
import com.lazydevs.notification.api.template.TemplateEvent;
import com.lazydevs.notification.api.template.TemplateEventAction;
import com.lazydevs.notification.api.template.TemplateFilter;
import com.lazydevs.notification.api.template.TemplateKey;
import com.lazydevs.notification.api.template.TemplateProblem;
import com.lazydevs.notification.api.template.TemplateStoreException;
import com.lazydevs.notification.api.template.TemplateStoreException.Reason;
import com.lazydevs.notification.api.template.TemplateSummary;
import com.lazydevs.notification.api.template.TemplateVersion;
import com.lazydevs.notification.api.template.TemplateVersionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * DD-38: the draft, publish, restore, discard and reset lifecycle of the in-memory store.
 */
class InMemoryTemplateStoreTest {

    private static final TemplateKey KEY = TemplateKey.of(null, "email", "WELCOME");
    private static final String FTL = TemplateVersion.FREEMARKER;

    private final InMemoryTemplateStore store = new InMemoryTemplateStore(64);

    @Test
    void draftRevisionsGuardConcurrentEdits() {
        TemplateVersion first = store.saveDraft(KEY, FTL, "a", "{\"x\":1}", 0, "ann", "start");
        assertThat(first.revision()).isEqualTo(1);
        assertThat(first.version()).isZero();
        assertThat(first.status()).isEqualTo(TemplateVersionStatus.DRAFT);
        assertThat(first.createdBy()).isEqualTo("ann");

        TemplateVersion second = store.saveDraft(KEY, FTL, "b", null, first.revision(), "bob", null);
        assertThat(second.revision()).isGreaterThan(first.revision());
        assertThat(second.createdBy()).isEqualTo("ann");
        assertThat(second.updatedBy()).isEqualTo("bob");

        TemplateStoreException stale = catchThrowableOfType(TemplateStoreException.class,
                () -> store.saveDraft(KEY, FTL, "c", null, first.revision(), "ann", null));
        assertThat(stale.getReason()).isEqualTo(Reason.REVISION_CONFLICT);
        assertThat(stale.getCurrentRevision()).isEqualTo(second.revision());
        assertThat(stale.getStatusCode()).isEqualTo(409);

        TemplateStoreException fresh = catchThrowableOfType(TemplateStoreException.class,
                () -> store.saveDraft(TemplateKey.of("t1", "email", "WELCOME"), FTL, "c", null, 3, "ann", null));
        assertThat(fresh.getCurrentRevision()).isZero();
    }

    @Test
    void contentAndSampleModelAreCappedInUtf8Bytes() {
        String tooBig = "é".repeat(33); // 66 bytes
        TemplateStoreException content = catchThrowableOfType(TemplateStoreException.class,
                () -> store.saveDraft(KEY, FTL, tooBig, null, 0, "ann", null));
        assertThat(content.getReason()).isEqualTo(Reason.SIZE_LIMIT);
        assertThat(content.getStatusCode()).isEqualTo(413);
        assertThat(content.getProblems()).extracting(TemplateProblem::reason)
                .containsExactly(TemplateProblem.Reason.SIZE_LIMIT);

        String bigModel = "x".repeat(65);
        assertThatThrownBy(() -> store.saveDraft(KEY, FTL, "ok", bigModel, 0, "ann", null))
                .isInstanceOf(TemplateStoreException.class).hasMessageContaining("sample model");
        assertThat(store.saveDraft(KEY, FTL, "x".repeat(64), null, 0, "ann", null).revision()).isEqualTo(1);
    }

    @Test
    void writesNeedAnActorAndAKnownContentType() {
        assertThatThrownBy(() -> store.saveDraft(KEY, FTL, "a", null, 0, " ", null))
                .isInstanceOf(TemplateStoreException.class).hasMessageContaining("actor");
        assertThatThrownBy(() -> store.saveDraft(KEY, "xml", "a", null, 0, "ann", null))
                .isInstanceOf(TemplateStoreException.class).hasMessageContaining("contentType");
        assertThatThrownBy(() -> store.saveDraft(KEY, FTL, null, null, 0, "ann", null))
                .isInstanceOf(TemplateStoreException.class).hasMessageContaining("content");
    }

    @Test
    void publishRestoreResetLifecycle() {
        store.saveDraft(KEY, FTL, "v1", "{}", 0, "ann", null);
        TemplateVersion v1 = store.publish(KEY, 1, "ann", "first");
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.status()).isEqualTo(TemplateVersionStatus.PUBLISHED);
        assertThat(v1.publishedBy()).isEqualTo("ann");
        assertThat(store.findDraft(KEY)).isEmpty();

        TemplateVersion draft2 = store.saveDraft(KEY, FTL, "v2", null, 0, "bob", null);
        assertThat(draft2.revision()).as("never reused after a publish").isGreaterThan(v1.revision());
        long staleRevision = v1.revision();
        assertThatThrownBy(() -> store.publish(KEY, staleRevision, "bob", "x"))
                .isInstanceOf(TemplateStoreException.class).hasMessageContaining("revision");
        TemplateVersion v2 = store.publish(KEY, draft2.revision(), "bob", "second");
        assertThat(v2.version()).isEqualTo(2);
        assertThat(store.findPublished(KEY)).map(TemplateVersion::content).contains("v2");

        TemplateVersion v3 = store.restore(KEY, 1, "cat", "back");
        assertThat(v3.version()).isEqualTo(3);
        assertThat(v3.content()).isEqualTo("v1");
        assertThat(v3.publishedBy()).isEqualTo("cat");
        assertThat(store.versions(KEY)).extracting(TemplateVersion::version, TemplateVersion::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(3, TemplateVersionStatus.PUBLISHED),
                        org.assertj.core.groups.Tuple.tuple(2, TemplateVersionStatus.SUPERSEDED),
                        org.assertj.core.groups.Tuple.tuple(1, TemplateVersionStatus.SUPERSEDED));

        store.archive(KEY, "cat", "reset");
        assertThat(store.findPublished(KEY)).isEmpty();
        assertThat(store.versions(KEY).getFirst().status()).isEqualTo(TemplateVersionStatus.ARCHIVED);
        assertThat(catchThrowableOfType(TemplateStoreException.class, () -> store.archive(KEY, "cat", null))
                .getReason()).isEqualTo(Reason.NO_OVERRIDE);

        TemplateVersion v4 = store.restore(KEY, 3, "cat", "again");
        assertThat(v4.version()).isEqualTo(4);
        assertThat(catchThrowableOfType(TemplateStoreException.class, () -> store.restore(KEY, 99, "cat", null))
                .getReason()).isEqualTo(Reason.UNKNOWN_VERSION);

        List<TemplateEvent> events = store.events(KEY, 100);
        assertThat(events).extracting(TemplateEvent::action).containsExactly(
                TemplateEventAction.RESTORED, TemplateEventAction.RESET, TemplateEventAction.RESTORED,
                TemplateEventAction.PUBLISHED, TemplateEventAction.CREATED_DRAFT,
                TemplateEventAction.PUBLISHED, TemplateEventAction.CREATED_DRAFT);
        assertThat(events.getFirst().version()).isEqualTo(4);
        assertThat(events.getFirst().actor()).isEqualTo("cat");
        assertThat(store.events(KEY, 2)).hasSize(2);
    }

    @Test
    void aStaleRevisionNeverMatchesALaterDraft() {
        TemplateVersion read = store.saveDraft(KEY, FTL, "a", null, 0, "ann", null);
        store.discardDraft(KEY, "bob", "start over");
        TemplateVersion replacement = store.saveDraft(KEY, FTL, "b", null, 0, "bob", null);

        assertThat(replacement.revision()).isGreaterThan(read.revision());
        assertThat(catchThrowableOfType(TemplateStoreException.class,
                () -> store.saveDraft(KEY, FTL, "a2", null, read.revision(), "ann", null)).getReason())
                .isEqualTo(Reason.REVISION_CONFLICT);
        assertThat(catchThrowableOfType(TemplateStoreException.class,
                () -> store.publish(KEY, read.revision(), "ann", "n")).getReason())
                .isEqualTo(Reason.REVISION_CONFLICT);
        assertThat(store.findDraft(KEY)).map(TemplateVersion::content).contains("b");
    }

    @Test
    void discardNeedsADraft() {
        assertThat(catchThrowableOfType(TemplateStoreException.class, () -> store.discardDraft(KEY, "ann", null))
                .getReason()).isEqualTo(Reason.NO_DRAFT);
        assertThat(catchThrowableOfType(TemplateStoreException.class, () -> store.publish(KEY, 1, "ann", null))
                .getReason()).isEqualTo(Reason.NO_DRAFT);

        store.saveDraft(KEY, FTL, "a", null, 0, "ann", null);
        store.discardDraft(KEY, "ann", "nope");
        assertThat(store.findDraft(KEY)).isEmpty();
        assertThat(store.events(KEY, 1).getFirst().action()).isEqualTo(TemplateEventAction.DISCARDED);
    }

    @Test
    void approvalStatusDecidesSendability() {
        store.saveDraft(KEY, TemplateVersion.JSON, "{}", null, 0, "ann", null);
        store.publish(KEY, 1, "ann", "n");
        assertThat(store.findPublished(KEY).orElseThrow().isSendable()).isTrue();

        TemplateVersion submitted =
                store.updateApproval(KEY, 1, TemplateApprovalStatus.SUBMITTED, "ref-1", null, "sys");
        assertThat(submitted.isSendable()).isFalse();
        assertThat(store.findPublished(KEY).orElseThrow().providerRef()).isEqualTo("ref-1");

        store.updateApproval(KEY, 1, TemplateApprovalStatus.APPROVED, "ref-1", null, "sys");
        assertThat(store.findPublished(KEY).orElseThrow().isSendable()).isTrue();
        assertThat(store.events(KEY, 1).getFirst().note()).isEqualTo("APPROVED");
        assertThatThrownBy(() -> store.updateApproval(KEY, 9, TemplateApprovalStatus.APPROVED, null, null, "sys"))
                .isInstanceOf(TemplateStoreException.class);
    }

    @Test
    void summariesCarryNoContentAndHonourTheFilter() {
        TemplateKey acme = TemplateKey.of("acme", "email", "WELCOME");
        TemplateKey sms = TemplateKey.of(null, "sms", "OTP");
        store.saveDraft(KEY, FTL, "d", "{}", 0, "ann", null);
        store.publish(KEY, 1, "ann", "n");
        store.saveDraft(KEY, FTL, "d2", "{}", 0, "ann", null);
        store.saveDraft(acme, FTL, "a", null, 0, "ann", null);
        store.saveDraft(sms, FTL, "s", null, 0, "ann", null);

        List<TemplateSummary> email = store.summaries(new TemplateFilter("email", null));
        assertThat(email).extracting(TemplateSummary::key).containsExactly(acme, KEY);
        TemplateSummary defaults = email.get(1);
        assertThat(defaults.published().version()).isEqualTo(1);
        assertThat(defaults.published().content()).isNull();
        assertThat(defaults.draft().content()).isNull();
        assertThat(defaults.draft().sampleModel()).isNull();
        assertThat(store.summaries(TemplateFilter.all())).hasSize(3);
        assertThat(store.summaries(new TemplateFilter(null, "OTP"))).extracting(TemplateSummary::key)
                .containsExactly(sms);
    }

    @Test
    void limitMustBePositive() {
        assertThatThrownBy(() -> new InMemoryTemplateStore(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
