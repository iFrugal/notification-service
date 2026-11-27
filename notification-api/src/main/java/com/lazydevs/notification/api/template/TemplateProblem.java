package com.lazydevs.notification.api.template;

/**
 * One reason a template cannot be saved or published, located in its source where
 * possible (DD-38).
 *
 * @param reason       what is wrong
 * @param message      description without a stack trace
 * @param line         1-based line in the checked source, {@code null} when unknown or when the
 *                     problem is in another template (see {@code templateName})
 * @param column       1-based column on that line, {@code null} when unknown
 * @param variablePath data-model path of the missing or invalid value, {@code null} when not
 *                     determinable
 * @param templateName template the problem is in when it is not the checked one, for example
 *                     an include; {@code null} otherwise
 * @since 1.3.0
 */
public record TemplateProblem(Reason reason, String message, Integer line, Integer column,
                              String variablePath, String templateName) {

    /** Problem categories; the first five match {@code TemplateRenderException.Reason}. */
    public enum Reason {
        NOT_FOUND,
        INVALID_TEMPLATE,
        MODEL_INVALID,
        EMPTY_OUTPUT,
        PARAMETER_INVALID,
        /** The content or the sample model is larger than the configured limit. */
        SIZE_LIMIT
    }

    /** A problem without a location. */
    public static TemplateProblem of(Reason reason, String message) {
        return new TemplateProblem(reason, message, null, null, null, null);
    }
}
