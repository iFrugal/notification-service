package com.lazydevs.notification.api.template;

import com.lazydevs.notification.api.exception.NotificationException;

import java.util.List;

/**
 * A template store or admin operation was refused (DD-38). {@link #getReason()} says why and
 * maps to one HTTP status, which {@link #getStatusCode()} carries for REST hosts.
 *
 * @since 1.3.0
 */
@SuppressWarnings("java:S110") // depth comes from the NotificationException tree shipped in 1.2.0
public class TemplateStoreException extends NotificationException {

    /**
     * Why the operation was refused; each reason has a stable error code and HTTP status.
     */
    public enum Reason {
        /** The template exists in no layer. HTTP 404. */
        NOT_FOUND(404),
        /** The key has no draft to publish or discard. HTTP 404. */
        NO_DRAFT(404),
        /** The layer has no published override to reset. HTTP 404. */
        NO_OVERRIDE(404),
        /** The key has no such version to restore. HTTP 404. */
        UNKNOWN_VERSION(404),
        /** The draft changed since the caller read it; see {@link #getCurrentRevision()}. HTTP 409. */
        REVISION_CONFLICT(409),
        /** The content or the sample model is larger than the configured limit. HTTP 413. */
        SIZE_LIMIT(413),
        /** Publishing was refused because the draft does not render; see {@link #getProblems()}. HTTP 422. */
        VALIDATION_FAILED(422),
        /** The channel is not editable in this deployment. HTTP 403. */
        READ_ONLY_CHANNEL(403),
        /** A required value is missing or malformed. HTTP 400. */
        INVALID_REQUEST(400);

        private final int status;

        Reason(int status) {
            this.status = status;
        }

        /** HTTP status for this reason. */
        public int status() {
            return status;
        }
    }

    private final Reason reason;
    private final transient TemplateKey key;
    private final long currentRevision;
    private final transient List<TemplateProblem> problems;

    public TemplateStoreException(Reason reason, TemplateKey key, String message) {
        this(reason, key, message, 0, List.of());
    }

    /**
     * @param currentRevision the draft's current revision for {@link Reason#REVISION_CONFLICT},
     *                        {@code 0} when there is no draft
     * @param problems        the problems behind {@link Reason#VALIDATION_FAILED}
     */
    public TemplateStoreException(Reason reason, TemplateKey key, String message, long currentRevision,
                                  List<TemplateProblem> problems) {
        super(reason.name(), message, reason.status());
        this.reason = reason;
        this.key = key;
        this.currentRevision = currentRevision;
        this.problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /** A {@link Reason#REVISION_CONFLICT}. */
    public static TemplateStoreException revisionConflict(TemplateKey key, long expected, long current) {
        return new TemplateStoreException(Reason.REVISION_CONFLICT, key,
                "Draft of " + key.name() + " is at revision " + current + ", not " + expected, current, List.of());
    }

    public Reason getReason() {
        return reason;
    }

    /** The template the operation was about, may be {@code null}. */
    public TemplateKey getKey() {
        return key;
    }

    /** The draft's current revision for a {@link Reason#REVISION_CONFLICT}; {@code 0} when there is no draft. */
    public long getCurrentRevision() {
        return currentRevision;
    }

    /** The problems behind a {@link Reason#VALIDATION_FAILED}; empty otherwise. */
    public List<TemplateProblem> getProblems() {
        return problems;
    }
}
