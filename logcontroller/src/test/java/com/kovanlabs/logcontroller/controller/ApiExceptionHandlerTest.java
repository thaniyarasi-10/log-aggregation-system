package com.kovanlabs.logcontroller.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ApiExceptionHandlerTest {

    private ApiExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ApiExceptionHandler();
    }

    @Test
    void handleResponseStatusException_403_returnsCorrectStatusAndMessage() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");

        ResponseEntity<ApiExceptionHandler.ErrorPayload> response =
                handler.handleResponseStatusException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(403);
        assertThat(response.getBody().message()).isEqualTo("Access denied");
        assertThat(response.getBody().timestamp()).isNotBlank();
    }

    @Test
    void handleResponseStatusException_404_returnsNotFound() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");

        ResponseEntity<ApiExceptionHandler.ErrorPayload> response =
                handler.handleResponseStatusException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().message()).isEqualTo("User not found");
    }

    @Test
    void handleResponseStatusException_nullReason_returnsDefaultMessage() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.BAD_REQUEST, null);

        ResponseEntity<ApiExceptionHandler.ErrorPayload> response =
                handler.handleResponseStatusException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("Request failed");
    }

    @Test
    void handleResponseStatusException_blankReason_returnsDefaultMessage() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.BAD_REQUEST, "   ");

        ResponseEntity<ApiExceptionHandler.ErrorPayload> response =
                handler.handleResponseStatusException(ex);

        assertThat(response.getBody().message()).isEqualTo("Request failed");
    }

    @Test
    void handleDataIntegrityViolation_returns400WithInvalidRequestDataMessage() {
        DataIntegrityViolationException ex =
                new DataIntegrityViolationException("Unique constraint violated");

        ResponseEntity<ApiExceptionHandler.ErrorPayload> response =
                handler.handleDataIntegrityViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().message()).isEqualTo("Invalid request data");
    }

    @Test
    void handleDataAccess_returns503WithDatabaseUnavailableMessage() {
        DataAccessException ex = new DataAccessException("Connection refused") {};

        ResponseEntity<ApiExceptionHandler.ErrorPayload> response =
                handler.handleDataAccess(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().status()).isEqualTo(503);
        assertThat(response.getBody().message()).isEqualTo("Database temporarily unavailable");
    }

    @Test
    void handleGeneric_returns500WithUnexpectedErrorMessage() {
        Exception ex = new RuntimeException("Something exploded");

        ResponseEntity<ApiExceptionHandler.ErrorPayload> response =
                handler.handleGeneric(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
    }

    @Test
    void handleGeneric_nullPointerException_returns500() {
        ResponseEntity<ApiExceptionHandler.ErrorPayload> response =
                handler.handleGeneric(new NullPointerException("null ref"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void errorPayload_timestampIsIso8601Format() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad");
        ResponseEntity<ApiExceptionHandler.ErrorPayload> response =
                handler.handleResponseStatusException(ex);

        // Timestamp must be parseable as an Instant
        assertThat(response.getBody().timestamp()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
    }
}
