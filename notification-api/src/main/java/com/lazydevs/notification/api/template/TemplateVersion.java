package com.lazydevs.notification.api.template;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;

import java.time.Instant;
import java.util.Objects;

/**
 * One stored version of a template (DD-38): the draft, the published override or an
 * earlier one.
 *
 * <p>Components:
 * <ul>
 *   <li>{@code key}: the template</li>
 *   <li>{@code version}: version number, from 1; {@code 0} for the draft</li>
 *   <li>{@code status}: lifecycle status</li>
 *   <li>{@code contentType}: {@value #FREEMARKER} or {@value #JSON}</li>
 *   <li>{@code content}: source text; {@code null} in a {@link TemplateSummary}</li>
 *   <li>{@code sampleModel}: sample data model as a JSON object text, may be {@code null};
 *       {@code null} in a {@link TemplateSummary}</li>
 *   <li>{@code revision}: optimistic-lock token of the draft: it grows with every save and is never
 *       reused for the key, even after a discard or a publish; kept on publish</li>
 *   <li>{@code createdBy}: actor who created the draft</li>
 *   <li>{@code createdAt}: when the draft was created</li>
 *   <li>{@code updatedBy}: actor of the last draft save</li>
 *   <li>{@code updatedAt}: when the draft was last saved</li>
 *   <li>{@code publishedBy}: actor who published or restored it, {@code null} for the draft</li>
 *   <li>{@code publishedAt}: when it was published, {@code null} for the draft</li>
 *   <li>{@code note}: note of the last change</li>
 *   <li>{@code approvalStatus}: approval at the provider, {@code null} when not tracked</li>
 *   <li>{@code providerRef}: the provider's id for the template, may be {@code null}</li>
 *   <li>{@code providerReason}: the provider's reason for a rejection or pause, may be {@code null}</li>
 * </ul>
 *
 * @since 1.3.0
 */
@Builder(toBuilder = true)
public record TemplateVersion(
        TemplateKey key,
        int version,
        TemplateVersionStatus status,
        String contentType,
        String content,
        String sampleModel,
        long revision,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt,
        String publishedBy,
        Instant publishedAt,
        String note,
        TemplateApprovalStatus approvalStatus,
        String providerRef,
        String providerReason) {

    /** Content type of FreeMarker templates (email, SMS, push). */
    public static final String FREEMARKER = "freemarker";

    /** Content type of structured bindings, such as WhatsApp template bindings. */
    public static final String JSON = "json";

    public TemplateVersion {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(status, "status");
    }

    /**
     * Whether a provider may be asked to send it: approval is not tracked ({@code null}) or
     * the provider approved it.
     */
    @JsonIgnore
    public boolean isSendable() {
        return approvalStatus == null || approvalStatus == TemplateApprovalStatus.APPROVED;
    }

    /** This version without its content and sample model, as listings carry it. */
    public TemplateVersion withoutContent() {
        return toBuilder().content(null).sampleModel(null).build();
    }
}
