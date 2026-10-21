package com.payflow.auth;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.web.filter.OncePerRequestFilter;

import com.payflow.common.Hashing;
import com.payflow.merchant.MerchantRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The security guard in front of /payments/**. Same shape as JwtAuthFilter,
 * but it checks an API key (for the shop's server) instead of a login token (for a person).
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final MerchantRepository merchants;

    public ApiKeyAuthFilter(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 1. Is there a key?
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer pf_live_")) {
            AuthErrors.write(response, "missing_api_key", "Send 'Authorization: Bearer <api_key>'");
            return;
        }
        String apiKey = header.substring("Bearer ".length());

        // 2. Scramble it the same way we did at registration, and 3. look for a match.
        Optional<UUID> merchantId = merchants.findIdByApiKeyHash(Hashing.sha256Hex(apiKey));
        if (merchantId.isEmpty()) {
            AuthErrors.write(response, "invalid_api_key", "API key is not valid");
            return;
        }

        // Found: remember who this is, and let the request continue.
        request.setAttribute(JwtAuthFilter.MERCHANT_ID, merchantId.get());
        chain.doFilter(request, response);
    }
}
