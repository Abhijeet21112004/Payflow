package com.payflow.common;

import org.springframework.http.HttpStatus;

/**
 * Throw this anywhere to stop a request and send the client an error reply,
 * e.g. new ApiException(HttpStatus.CONFLICT, "email_taken", "...").
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
