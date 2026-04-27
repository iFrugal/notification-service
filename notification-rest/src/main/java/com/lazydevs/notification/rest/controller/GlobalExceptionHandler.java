package com.lazydevs.notification.rest.controller;

import com.lazydevs.notification.api.exception.IdempotencyInProgressException;
import com.lazydevs.notification.api.exception.RateLimitExceededException;
import com.lazydevs.notification.api.idempotency.IdempotencyStatus;
import lazydevs.services.basic.handler.RESTExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
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

    /**
     * Render a rate-limit denial per DD-12:
     * <pre>
     * HTTP/1.1 429 Too Many Requests
     * Retry-After: 3
     * Content-Type: application/json
     *
     * { "error": "RATE_LIMIT_EXCEEDED", "retryAfterSeconds": 3, "message": "..." }
     * </pre>
     *
     * <p>Retry-After is rounded up to whole seconds — the HTTP spec
     * doesn't allow sub-second precision (RFC 7231 §7.1.3). A token
     * available in 200ms still surfaces as {@code Retry-After: 1} so
     * compliant clients always wait at least as long as the bucket needs.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitExceededException e) {
        long retryAfterSeconds = Math.max(1L,
                (e.getRetryAfter().toMillis() + 999L) / 1000L);
        log.warn("Rate limit exceeded: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "RATE_LIMIT_EXCEEDED");
        body.put("retryAfterSeconds", retryAfterSeconds);
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(body);
    }
}
