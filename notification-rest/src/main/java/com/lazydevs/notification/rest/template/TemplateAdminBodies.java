package com.lazydevs.notification.rest.template;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lazydevs.notification.api.template.TemplateEvent;
import com.lazydevs.notification.api.template.TemplateProblem;
import com.lazydevs.notification.api.template.TemplateSummary;
import com.lazydevs.notification.api.template.TemplateVersion;
import com.lazydevs.notification.core.template.admin.TemplateListItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Request and response bodies of {@link TemplateAdminController} (DD-38).
 *
 * @since 1.3.0
 */
public final class TemplateAdminBodies {

    private TemplateAdminBodies() {
    }

    /** Body of preview and validate. */
    @Schema(description = "Unsaved template content and the data model to render it with")
    public record CheckRequest(
            @Schema(description = "Template source; FreeMarker, or a JSON object for WhatsApp bindings")
            String content,
            @Schema(description = "Data model; absent is an empty model")
            Map<String, Object> model) {
    }

    /** Body of a draft save. */
    @Schema(description = "Draft to save")
    public record DraftRequest(
            @Schema(description = "Template source", requiredMode = Schema.RequiredMode.REQUIRED)
            String content,
            @Schema(description = "Sample data model stored with the draft and used to validate it on publish."
                    + " Use made-up data, never real recipients.")
            Map<String, Object> sampleModel,
            @Schema(description = "Draft revision last read; 0 when there was no draft",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            long expectedRevision,
            @Schema(description = "Note for the audit trail")
            String note) {
    }

    /** Body of a publish. */
    @Schema(description = "Draft revision to publish and why")
    public record PublishRequest(
            @Schema(description = "Draft revision last read", requiredMode = Schema.RequiredMode.REQUIRED)
            long draftRevision,
            @Schema(description = "Why the change is made; required", requiredMode = Schema.RequiredMode.REQUIRED)
            String note) {
    }

    /** Body of a restore or a reset. */
    @Schema(description = "Note for the audit trail")
    public record NoteRequest(String note) {
    }

    /** The listing. */
    public record TemplateList(List<TemplateListItem> templates) {
    }

    /** Tenant-layer store entries. */
    public record TenantOverrides(List<TemplateSummary> overrides) {
    }

    /** A key's versions, newest first. */
    public record Versions(List<TemplateVersion> versions) {
    }

    /** A key's audit trail, newest first. */
    public record Events(List<TemplateEvent> events) {
    }

    /** Error body of every refused call. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Why a call was refused")
    public record ErrorBody(
            @Schema(description = "Error code: NOT_FOUND, NO_DRAFT, NO_OVERRIDE, UNKNOWN_VERSION,"
                    + " REVISION_CONFLICT, SIZE_LIMIT, VALIDATION_FAILED, READ_ONLY_CHANNEL, INVALID_REQUEST"
                    + " or UNAUTHENTICATED")
            String error,
            String message,
            @Schema(description = "The draft's current revision, for REVISION_CONFLICT; 0 when there is no draft")
            Long currentRevision,
            @Schema(description = "What is wrong, for VALIDATION_FAILED and SIZE_LIMIT")
            List<TemplateProblem> errors) {
    }
}
