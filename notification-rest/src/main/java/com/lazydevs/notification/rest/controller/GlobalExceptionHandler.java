package com.lazydevs.notification.rest.controller;

import com.lazydevs.notification.api.exception.IdempotencyInProgressException;
import com.lazydevs.notification.api.idempotency.IdempotencyStatus;
import lazydevs.services.basic.handler.RESTExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global exception handler for notification REST controllers.
 *
 * Extends RESTExceptionHandler from app-building-commons which handles:
 * - RESTException (and all subclasses including NotificationException)
 * - MethodArgumentNotValidException
 * - MethodArgumentTypeMismatchException
 * - Generic exceptions
 *
 * Since NotificationException extends RESTException with proper status codes,
 * most notification-specific exceptions are handled automatically. The one
 * special case is {@link IdempotencyInProgressException} — DD-10 mandates a
 * specific 409 body shape ({@code {notificationId, status}}) that doesn't
 * match the inherited generic-error envelope, so we override it here.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends RESTExceptionHandler {

    /**
     * Render an in-flight idempotency conflict per DD-10 §Semantics:
     * <pre>
     * HTTP/1.1 409 Conflict
     * Content-Type: application/json
     *
     * { "notificationId": "...", "status": "IN_PROGRESS" }
     * </pre>
     * Without this override, the inherited handler would render the
     * generic {@code {errorCode, message}} envelope, which clients
     * implementing the DD-10 contract would have to special-case.
     */
    @ExceptionHandler(IdempotencyInProgressException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyInProgress(IdempotencyInProgressException e) {
        log.debug("Idempotent send conflict — concurrent in-flight notificationId={}",
                e.getInProgressNotificationId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notificationId", e.getInProgressNotificationId());
        body.put("status", IdempotencyStatus.IN_PROGRESS.name());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
