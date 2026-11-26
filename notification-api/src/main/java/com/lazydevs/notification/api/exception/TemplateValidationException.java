package com.lazydevs.notification.api.exception;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;

/**
 * The request's template data does not satisfy the rules of the target
 * template, for example a required variable is missing or a value breaks the
 * channel's limits. A client error: resending the same request cannot succeed.
 * Returns HTTP 400 Bad Request.
 *
 * <p>The message names the template and the variable, never the value, which
 * may be personal data.
 *
 * @since 1.4.0
 */
public class TemplateValidationException extends NotificationException {

    /** Error code of every template validation failure. */
    public static final String ERROR_CODE = "TEMPLATE_VALIDATION_FAILED";

    public TemplateValidationException(String message) {
        super(ERROR_CODE, message, HTTP_BAD_REQUEST);
    }
}
