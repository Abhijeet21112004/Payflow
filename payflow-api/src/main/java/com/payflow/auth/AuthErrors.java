package com.payflow.auth;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

/** Filters run before Spring's error handling, so they write their 401 replies themselves. */
final class AuthErrors {

    private AuthErrors() {
    }

    static void write(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}");
    }
}
