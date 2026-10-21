package com.payflow.common;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns exceptions into error replies, so every error has the same shape:
 * {"error": {"code": "...", "message": "..."}}
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException e) {
        return error(e.getStatus(), e.getCode(), e.getMessage());
    }

    // Thrown by Spring when a request body fails its @Valid checks (e.g. a blank email).
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        var fieldError = e.getBindingResult().getFieldErrors().getFirst();
        String message = fieldError.getField() + " " + fieldError.getDefaultMessage();
        return error(HttpStatus.BAD_REQUEST, "invalid_request", message);
    }

    // The body isn't valid JSON, or a field has the wrong type (e.g. "amount": "abc").
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", "Request body is not valid JSON for this endpoint");
    }

    // A URL part has the wrong type, e.g. /payments/not-a-uuid.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", e.getName() + " is not valid");
    }

    // Anything else. Spring's own errors (404 unknown URL, 405 wrong method, …) keep their status;
    // everything unexpected becomes a 500 and is logged.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        if (e instanceof ErrorResponse springError) {
            HttpStatusCode status = springError.getStatusCode();
            return error(status, "request_error", springError.getBody().getDetail());
        }
        log.error("Unhandled error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Something went wrong");
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatusCode status, String code, String message) {
        return ResponseEntity.status(status)
                .body(Map.of("error", Map.of("code", code, "message", message == null ? "" : message)));
    }
}
