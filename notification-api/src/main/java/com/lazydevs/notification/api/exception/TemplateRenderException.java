package com.lazydevs.notification.api.exception;

/**
 * A template could not be turned into sendable content: it is missing, it is not valid
 * FreeMarker, the data model does not fit it, or it rendered to nothing usable (DD-27).
 * A channel that renders its own content, such as WhatsApp, also reports a value that
 * breaks the channel's parameter rules with it (DD-35).
 * <p>
 * With {@code notification.template.validate-before-dispatch=true} the send path throws
 * this to the caller before anything is stored, rate limited or dead-lettered, and the
 * REST module answers HTTP 422 with {@link #getErrorCode()}, {@link #getReason()} and
 * {@link #getVariablePath()}.
 * With the flag off (the 1.x default) it is caught by the send path like any other
 * {@link NotificationException} and becomes a {@code FAILED} response.
 * <p>
 * {@link TemplateNotFoundException} is the {@link Reason#NOT_FOUND} subclass, so callers
 * that catch it keep working.
 *
 * @since 1.3.0
 */
@SuppressWarnings("java:S110") // depth comes from the NotificationException tree shipped in 1.2.0
public class TemplateRenderException extends NotificationException {

    /** HTTP 422 Unprocessable Content; not present in {@link java.net.HttpURLConnection}. */
    public static final int HTTP_UNPROCESSABLE_ENTITY = 422;

    /**
     * Why rendering failed. Each reason has its own stable error code.
     */
    public enum Reason {
        /** No template exists for the tenant, channel and template id (or an id is invalid). */
        NOT_FOUND("TEMPLATE_NOT_FOUND"),
        /** The template source is not valid FreeMarker, or an {@code <#include>} it names is missing. */
        INVALID_TEMPLATE("TEMPLATE_INVALID"),
        /** The data model does not fit the template: a missing, null or wrongly typed value. */
        MODEL_INVALID("TEMPLATE_MODEL_INVALID"),
        /** The template rendered, but to a blank email subject or an empty body. */
        EMPTY_OUTPUT("TEMPLATE_EMPTY_OUTPUT"),
        /**
         * A value is present, but breaks the channel's rules for a template parameter, for
         * example a WhatsApp parameter with a new line, a run of spaces or too many characters,
         * or a button index out of range. {@link TemplateRenderException#getVariablePath()}
         * names the variable.
         *
         * @since 1.4.0
         */
        PARAMETER_INVALID("TEMPLATE_PARAMETER_INVALID");

        private final String errorCode;

        Reason(String errorCode) {
            this.errorCode = errorCode;
        }

        /** The error code reported for this reason. */
        public String errorCode() {
            return errorCode;
        }
    }

    private final Reason reason;
    private final String tenantId;
    private final String templateId;
    private final String variablePath;

    /**
     * @param reason       why rendering failed
     * @param tenantId     tenant whose template was rendered, or {@code null} when unknown
     * @param templateId   template id, or {@code null} when unknown or not safe to repeat
     * @param variablePath data-model path of the missing or invalid value (for example
     *                     {@code order.items[2].price}), or {@code null} when not determinable
     * @param message      description without a stack trace
     * @param cause        underlying engine exception, or {@code null}
     */
    public TemplateRenderException(Reason reason, String tenantId, String templateId, String variablePath,
                                   String message, Throwable cause) {
        this(reason, tenantId, templateId, variablePath, message, cause, HTTP_UNPROCESSABLE_ENTITY);
    }

    /**
     * For subclasses that keep an older HTTP status ({@link TemplateNotFoundException} keeps 404).
     */
    protected TemplateRenderException(Reason reason, String tenantId, String templateId, String variablePath,
                                      String message, Throwable cause, int statusCode) {
        super(reason.errorCode(), message, cause);
        statusCode(statusCode);
        this.reason = reason;
        this.tenantId = tenantId;
        this.templateId = templateId;
        this.variablePath = variablePath;
    }

    public Reason getReason() {
        return reason;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getTemplateId() {
        return templateId;
    }

    /**
     * Data-model path of the value that broke the render, rebased to the outermost
     * data-model reference when the value reached the failing expression through a
     * {@code #function} or {@code #macro} argument; {@code null} when not determinable.
     */
    public String getVariablePath() {
        return variablePath;
    }
}
