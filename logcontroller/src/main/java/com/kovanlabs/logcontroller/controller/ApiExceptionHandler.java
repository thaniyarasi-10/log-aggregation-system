package com.kovanlabs.logcontroller.controller;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Global exception handler for all REST controllers.
 *
 * <p>Handler precedence (most specific first):
 * <ol>
 *   <li>{@link ResponseStatusException} — explicit HTTP status set by controller logic</li>
 *   <li>{@link DataIntegrityViolationException} — constraint violations → 400</li>
 *   <li>{@link DataAccessException} — DB connectivity issues → 503</li>
 *   <li>{@link Exception} — any other unhandled exception → 500 (logged at ERROR)</li>
 * </ol>
 *
 * <p>The generic handler uses HTTP 500 (Internal Server Error), not 503.
 * 503 is reserved for genuine infrastructure unavailability (DB down, etc.).
 * Mapping every unexpected exception to 503 hides bugs and misleads callers.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorPayload> handleResponseStatusException(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String message = ex.getReason() == null || ex.getReason().isBlank()
                ? "Request failed"
                : ex.getReason();
        return ResponseEntity.status(status)
                .body(new ErrorPayload(message, status.value(), Instant.now().toString()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorPayload> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        LOGGER.warn("Data integrity violation: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorPayload(
                        "Invalid request data",
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now().toString()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorPayload> handleDataAccess(DataAccessException ex) {
        LOGGER.error("Database access failure: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorPayload(
                        "Database temporarily unavailable",
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        Instant.now().toString()));
    }

    /**
     * Catch-all for any exception not handled above.
     *
     * <p>Returns HTTP 500 (not 503) and logs the full stack trace at ERROR level.
     * This makes unexpected failures immediately visible in logs rather than
     * silently masking them as "service unavailable".
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorPayload> handleGeneric(Exception ex) {
        LOGGER.error("Unhandled exception in request handler: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorPayload(
                        "An unexpected error occurred",
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        Instant.now().toString()));
    }

    public record ErrorPayload(String message, int status, String timestamp) {
    }
}
