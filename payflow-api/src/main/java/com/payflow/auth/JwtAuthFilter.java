package com.payflow.auth;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The security guard in front of /merchants/me/**. Runs before the controller:
 * no valid token → 401 and the controller never runs;
 * valid token → the merchant id is attached to the request and it continues.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String MERCHANT_ID = "merchantId";

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            AuthErrors.write(response, "missing_token", "Send 'Authorization: Bearer <token>'");
            return;
        }

        Optional<UUID> merchantId = jwtService.verify(header.substring("Bearer ".length()));
        if (merchantId.isEmpty()) {
            AuthErrors.write(response, "invalid_token", "Token is invalid or expired");
            return;
        }

        request.setAttribute(MERCHANT_ID, merchantId.get());
        chain.doFilter(request, response);   // let the request continue to the controller
    }
}
