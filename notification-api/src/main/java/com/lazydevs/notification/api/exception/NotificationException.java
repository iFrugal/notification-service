package com.lazydevs.notification.api.exception;

import lazydevs.services.basic.exception.RESTException;

import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;

/**
 * Base exception for notification service errors.
 * Extends RESTException from app-building-commons for consistent error handling.
 */
public class NotificationException extends RESTException {

    public NotificationException(String message) {
        super(message, HTTP_INTERNAL_ERROR);
        this.errorCode("NOTIFICATION_ERROR");
    }

    public NotificationException(String errorCode, String message) {
        super(message, HTTP_INTERNAL_ERROR);
        this.errorCode(errorCode);
    }

    public NotificationException(String errorCode, String message, int statusCode) {
        super(message, statusCode);
        this.errorCode(errorCode);
    }

    public NotificationException(String errorCode, String message, Throwable cause) {
        super(message, cause, HTTP_INTERNAL_ERROR);
        this.errorCode(errorCode);
    }

    public NotificationException(String message, Throwable cause) {
        super(message, cause, HTTP_INTERNAL_ERROR);
        this.errorCode("NOTIFICATION_ERROR");
    }
}
