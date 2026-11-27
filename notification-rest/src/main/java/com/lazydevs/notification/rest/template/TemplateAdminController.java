package com.lazydevs.notification.rest.template;

import com.lazydevs.notification.api.template.TemplateKey;
import com.lazydevs.notification.api.template.TemplateStoreException;
import com.lazydevs.notification.api.template.TemplateVersion;
import com.lazydevs.notification.core.template.admin.TemplateAdminService;
import com.lazydevs.notification.core.template.admin.TemplateCheckResult;
import com.lazydevs.notification.core.template.admin.TemplateDetail;
import com.lazydevs.notification.rest.template.TemplateAdminBodies.CheckRequest;
import com.lazydevs.notification.rest.template.TemplateAdminBodies.DraftRequest;
import com.lazydevs.notification.rest.template.TemplateAdminBodies.ErrorBody;
import com.lazydevs.notification.rest.template.TemplateAdminBodies.Events;
import com.lazydevs.notification.rest.template.TemplateAdminBodies.NoteRequest;
import com.lazydevs.notification.rest.template.TemplateAdminBodies.PublishRequest;
import com.lazydevs.notification.rest.template.TemplateAdminBodies.TemplateList;
import com.lazydevs.notification.rest.template.TemplateAdminBodies.TenantOverrides;
import com.lazydevs.notification.rest.template.TemplateAdminBodies.Versions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Template admin API under {@code <base-path>/admin/templates} (DD-38): browse, preview,
 * edit, publish and roll back the template store's overrides of the built-in templates.
 * <p>
 * Off unless {@code notification.rest.admin.templates.enabled=true}, and, like every admin
 * endpoint, shipped without authentication (DD-08): the host secures the path. The actor of
 * each write comes from the {@link ActorResolver}. The layer is the {@code tenantId} query
 * parameter; absent is the default layer every tenant falls back to.
 *
 * @since 1.3.0
 */
@Slf4j
@RestController
@RequestMapping("${notification.rest.base-path:/api/v1}/admin/templates")
@Tag(name = "Template admin",
        description = "Browse, preview, edit, publish and roll back template overrides (DD-38). Overrides live in "
                + "the template store and win over the built-in files at their layer: a tenant, or the default "
                + "layer (no tenantId). Publishing validates the draft with its sample model. No authentication "
                + "is shipped; secure the path in the host.")
public class TemplateAdminController {

    private static final String TENANT_DESCRIPTION = "Tenant layer; absent for the default layer";
    private static final String CHANNEL_DESCRIPTION = "Channel, lower case: email, sms, whatsapp or push";
    private static final String TEMPLATE_DESCRIPTION = "Template id";
    private static final String ERROR_MEDIA = "application/json";

    private final TemplateAdminService service;
    private final ActorResolver actors;

    public TemplateAdminController(
            TemplateAdminService service,
            ActorResolver actors) {
        this.service = Objects.requireNonNull(service, "service");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    @GetMapping
    @Operation(summary = "List templates",
            description = "Every template known as a built-in file or a store entry, seen from the default layer: "
                    + "whether a default built-in file exists, the default override and draft, and how many "
                    + "tenants have their own override.")
    @ApiResponse(responseCode = "200", description = "The templates, sorted by channel and id")
    public TemplateList list(
            @Parameter(description = "Only this channel")
            @RequestParam(name = "channel", required = false) String channel) {
        return new TemplateList(service.list(channel));
    }

    @GetMapping("/tenant-overrides")
    @Operation(summary = "List tenant overrides",
            description = "Tenant-layer drafts and published overrides across tenants, without content.")
    @ApiResponse(responseCode = "200", description = "The overrides, by tenant")
    public TenantOverrides tenantOverrides(
            @Parameter(description = "Only this channel")
            @RequestParam(name = "channel", required = false) String channel,
            @Parameter(description = "Only this template")
            @RequestParam(name = "templateId", required = false) String templateId) {
        return new TenantOverrides(service.tenantOverrides(channel, templateId));
    }

    @GetMapping("/{channel}/{templateId}")
    @Operation(summary = "Get a template in one layer",
            description = "The layer's built-in file, what it inherits without its own override, the published "
                    + "override, the draft and the sample model.")
    @ApiResponse(responseCode = "200", description = "The template")
    @ApiResponse(responseCode = "404", description = "The template exists in no layer",
            content = @Content(mediaType = ERROR_MEDIA, schema = @Schema(implementation = ErrorBody.class)))
    public TemplateDetail get(
            @Parameter(description = CHANNEL_DESCRIPTION) @PathVariable("channel") String channel,
            @Parameter(description = TEMPLATE_DESCRIPTION) @PathVariable("templateId") String templateId,
            @Parameter(description = TENANT_DESCRIPTION)
            @RequestParam(name = "tenantId", required = false) String tenantId) {
        return service.get(key(tenantId, channel, templateId));
    }

    @PostMapping("/{channel}/{templateId}/preview")
    @Operation(summary = "Preview unsaved content",
            description = "Render the content with the model as the layer's tenant would. An email returns its "
                    + "subject, HTML and text parts. Rendering problems come back with ok=false, not as an error.")
    @ApiResponse(responseCode = "200", description = "The rendered parts, or the problems")
    public TemplateCheckResult preview(
            @Parameter(description = CHANNEL_DESCRIPTION) @PathVariable("channel") String channel,
            @Parameter(description = TEMPLATE_DESCRIPTION) @PathVariable("templateId") String templateId,
            @Parameter(description = TENANT_DESCRIPTION)
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestBody CheckRequest body) {
        return service.preview(key(tenantId, channel, templateId), body.content(), body.model());
    }

    @PostMapping("/{channel}/{templateId}/validate")
    @Operation(summary = "Validate unsaved content",
            description = "The checks a publish runs: size limit, FreeMarker syntax (with line and column), a "
                    + "render with the model (with the variable path of a missing value) and, for email, a "
                    + "subject and a body.")
    @ApiResponse(responseCode = "200", description = "ok, or the problems")
    public TemplateCheckResult validate(
            @Parameter(description = CHANNEL_DESCRIPTION) @PathVariable("channel") String channel,
            @Parameter(description = TEMPLATE_DESCRIPTION) @PathVariable("templateId") String templateId,
            @Parameter(description = TENANT_DESCRIPTION)
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestBody CheckRequest body) {
        return service.validate(key(tenantId, channel, templateId), body.content(), body.model());
    }

    @PutMapping("/{channel}/{templateId}/draft")
    @Operation(summary = "Save the draft",
            description = "Create or update the layer's draft. expectedRevision guards against overwriting "
                    + "someone else's change: 0 creates a draft, otherwise it must be the current revision.")
    @ApiResponse(responseCode = "200", description = "The saved draft")
    @ApiResponse(responseCode = "401", description = "No actor could be resolved",
            content = @Content(mediaType = ERROR_MEDIA, schema = @Schema(implementation = ErrorBody.class)))
    @ApiResponse(responseCode = "403", description = "The channel is read-only",
            content = @Content(mediaType = ERROR_MEDIA, schema = @Schema(implementation = ErrorBody.class)))
    @ApiResponse(responseCode = "409", description = "The draft is at another revision (currentRevision)",
            content = @Content(mediaType = ERROR_MEDIA, schema = @Schema(implementation = ErrorBody.class)))
    @ApiResponse(responseCode = "413", description = "The content or the sample model is too large",
            content = @Content(mediaType = ERROR_MEDIA, schema = @Schema(implementation = ErrorBody.class)))
    public TemplateVersion saveDraft(
            @Parameter(description = CHANNEL_DESCRIPTION) @PathVariable("channel") String channel,
            @Parameter(description = TEMPLATE_DESCRIPTION) @PathVariable("templateId") String templateId,
            @Parameter(description = TENANT_DESCRIPTION)
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestBody DraftRequest body,
            HttpServletRequest request) {
        return service.saveDraft(key(tenantId, channel, templateId), body.content(), body.sampleModel(),
                body.expectedRevision(), actor(request), body.note());
    }

    @DeleteMapping("/{channel}/{templateId}/draft")
    @Operation(summary = "Discard the draft")
    @ApiResponse(responseCode = "204", description = "Discarded")
    @ApiResponse(responseCode = "404", description = "There is no draft",
            content = @Content(mediaType = ERROR_MEDIA, schema = @Schema(implementation = ErrorBody.class)))
    public ResponseEntity<Void> discard(
            @Parameter(description = CHANNEL_DESCRIPTION) @PathVariable("channel") String channel,
            @Parameter(description = TEMPLATE_DESCRIPTION) @PathVariable("templateId") String templateId,
            @Parameter(description = TENANT_DESCRIPTION)
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @Parameter(description = "Note for the audit trail")
            @RequestParam(name = "note", required = false) String note,
            HttpServletRequest request) {
        service.discard(key(tenantId, channel, templateId), actor(request), note);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{channel}/{templateId}/publish")
    @Operation(summary = "Publish the draft",
            description = "Validate the draft with its sample model and publish it as the next version; the "
                    + "change reaches this replica at once and the others within the poll interval.")
    @ApiResponse(responseCode = "200", description = "The published version")
    @ApiResponse(responseCode = "400", description = "The note is missing",
            content = @Content(mediaType = ERROR_MEDIA, schema = @Schema(implementation = ErrorBody.class)))
    @ApiResponse(responseCode = "404", description = "There is no draft",
            content = @Content(mediaType = ERROR_MEDIA, schema = @Schema(implementation = ErrorBody.class)))
    @ApiResponse(responseCode = "409", description = "The draft is at another revision (currentRevision)",
            content = @Content(mediaType = ERROR_MEDIA, schema = @Schema(implementation = ErrorBody.class)))
    @ApiResponse(responseCode = "422", description = "The draft does not validate (errors)",
            content = @Content(mediaType = ERROR_MEDIA, schema = @Schema(implementation = ErrorBody.class)))
    public TemplateVersion publish(
            @Parameter(description = CHANNEL_DESCRIPTION) @PathVariable("channel") String channel,
            @Parameter(description = TEMPLATE_DESCRIPTION) @PathVariable("templateId") String templateId,
            @Parameter(description = TENANT_DESCRIPTION)
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestBody PublishRequest body,
            HttpServletRequest request) {
        return service.publish(key(tenantId, channel, templateId), body.draftRevision(), actor(request),
                body.note());
    }

    @PostMapping("/{channel}/{templateId}/versions/{version}/restore")
    @Operation(summary = "Restore a version",
            description = "Publish a copy of an earlier version as the next version. Not validated again, so a "
                    + "bad publish can always be rolled back.")
    @ApiResponse(responseCode = "200", description = "The new published version")
    @ApiResponse(responseCode = "404", description = "No such version",
            content = @Content(mediaType = ERROR_MEDIA, schema = @Schema(implementation = ErrorBody.class)))
    public TemplateVersion restore(
            @Parameter(description = CHANNEL_DESCRIPTION) @PathVariable("channel") String channel,
            @Parameter(description = TEMPLATE_DESCRIPTION) @PathVariable("templateId") String templateId,
            @Parameter(description = "Version to restore") @PathVariable("version") int version,
            @Parameter(description = TENANT_DESCRIPTION)
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestBody(required = false) NoteRequest body,
            HttpServletRequest request) {
        return service.restore(key(tenantId, channel, templateId), version, actor(request), note(body));
    }

    @PostMapping("/{channel}/{templateId}/reset")
    @Operation(summary = "Reset to the inherited template",
            description = "Archive the layer's published override, so sends fall back to the next layer: the "
                    + "built-in file, or for a tenant the default layer.")
    @ApiResponse(responseCode = "204", description = "Reset")
    @ApiResponse(responseCode = "404", description = "The layer has no published override",
            content = @Content(mediaType = ERROR_MEDIA, schema = @Schema(implementation = ErrorBody.class)))
    public ResponseEntity<Void> reset(
            @Parameter(description = CHANNEL_DESCRIPTION) @PathVariable("channel") String channel,
            @Parameter(description = TEMPLATE_DESCRIPTION) @PathVariable("templateId") String templateId,
            @Parameter(description = TENANT_DESCRIPTION)
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestBody(required = false) NoteRequest body,
            HttpServletRequest request) {
        service.reset(key(tenantId, channel, templateId), actor(request), note(body));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{channel}/{templateId}/versions")
    @Operation(summary = "List versions", description = "Published, superseded and archived versions with "
            + "content, newest first.")
    @ApiResponse(responseCode = "200", description = "The versions")
    public Versions versions(
            @Parameter(description = CHANNEL_DESCRIPTION) @PathVariable("channel") String channel,
            @Parameter(description = TEMPLATE_DESCRIPTION) @PathVariable("templateId") String templateId,
            @Parameter(description = TENANT_DESCRIPTION)
            @RequestParam(name = "tenantId", required = false) String tenantId) {
        return new Versions(service.versions(key(tenantId, channel, templateId)));
    }

    @GetMapping("/{channel}/{templateId}/events")
    @Operation(summary = "List the audit trail", description = "Who did what and when, newest first.")
    @ApiResponse(responseCode = "200", description = "The events")
    public Events events(
            @Parameter(description = CHANNEL_DESCRIPTION) @PathVariable("channel") String channel,
            @Parameter(description = TEMPLATE_DESCRIPTION) @PathVariable("templateId") String templateId,
            @Parameter(description = TENANT_DESCRIPTION)
            @RequestParam(name = "tenantId", required = false) String tenantId) {
        return new Events(service.events(key(tenantId, channel, templateId)));
    }

    // ---------------------------------------------------------------- errors

    @ExceptionHandler(TemplateStoreException.class)
    public ResponseEntity<ErrorBody> handleStore(
            TemplateStoreException e) {
        TemplateStoreException.Reason reason = e.getReason();
        ErrorBody body = new ErrorBody(reason.name(), e.getMessage(),
                reason == TemplateStoreException.Reason.REVISION_CONFLICT ? e.getCurrentRevision() : null,
                e.getProblems().isEmpty() ? null : e.getProblems());
        return ResponseEntity.status(reason.status()).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadable() {
        return ResponseEntity.badRequest().body(new ErrorBody(TemplateStoreException.Reason.INVALID_REQUEST.name(),
                "The request body is missing or not valid JSON", null, null));
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<ErrorBody> handleUnauthenticated(
            UnauthenticatedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorBody("UNAUTHENTICATED", e.getMessage(),
                null, null));
    }

    // ---------------------------------------------------------------- helpers

    private static TemplateKey key(String tenantId, String channel, String templateId) {
        try {
            return TemplateKey.of(tenantId, channel, templateId);
        } catch (IllegalArgumentException e) {
            throw new TemplateStoreException(TemplateStoreException.Reason.INVALID_REQUEST, null, e.getMessage());
        }
    }

    private String actor(HttpServletRequest request) {
        return actors.resolve(request).orElseThrow(() -> new UnauthenticatedException(
                "No actor: template changes need an authenticated caller"));
    }

    private static String note(NoteRequest body) {
        return body == null ? null : body.note();
    }

    /** A write without an actor. */
    static final class UnauthenticatedException extends RuntimeException {
        UnauthenticatedException(String message) {
            super(message);
        }
    }
}
