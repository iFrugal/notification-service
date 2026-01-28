package com.lazydevs.notification.rest.controller;

import lazydevs.services.basic.handler.RESTExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
 * all notification-specific exceptions are handled automatically.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends RESTExceptionHandler {
    // All exception handling is inherited from RESTExceptionHandler
    // NotificationException extends RESTException, so it's handled with correct status codes
}
