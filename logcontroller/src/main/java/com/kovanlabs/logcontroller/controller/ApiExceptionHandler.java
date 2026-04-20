package com.kovanlabs.logcontroller.controller;

import java.time.Instant;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorPayload> handleResponseStatusException(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String message = ex.getReason() == null || ex.getReason().isBlank()
                ? "Request failed"
                : ex.getReason();

        return ResponseEntity.status(status).body(new ErrorPayload(message, status.value(), Instant.now().toString()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorPayload> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorPayload("Invalid request data", HttpStatus.BAD_REQUEST.value(), Instant.now().toString()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorPayload> handleDataAccess(DataAccessException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorPayload(
                        "Database temporarily unavailable",
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        Instant.now().toString()
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorPayload> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorPayload(
                        "Service temporarily unavailable",
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        Instant.now().toString()
                ));
    }

    public record ErrorPayload(String message, int status, String timestamp) {
    }
}
