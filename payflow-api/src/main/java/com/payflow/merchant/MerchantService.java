package com.payflow.merchant;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.payflow.auth.JwtService;
import com.payflow.common.ApiException;
import com.payflow.common.Hashing;
import com.payflow.ledger.LedgerService;

/** Merchant logic: registration (hash → generate keys → save) and login (check password → issue token). */
@Service
public class MerchantService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MerchantRepository merchants;
    private final JwtService jwtService;
    private final LedgerService ledgerService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final String dummyHash = passwordEncoder.encode("dummy-password-for-timing");

    public MerchantService(MerchantRepository merchants, JwtService jwtService, LedgerService ledgerService) {
        this.merchants = merchants;
        this.jwtService = jwtService;
        this.ledgerService = ledgerService;
    }

    // One transaction: the merchant row and their ledger account are created together or not at all.
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();

        String passwordHash = passwordEncoder.encode(request.password());

        String apiKey = "pf_live_" + randomToken();
        String apiKeyHash = Hashing.sha256Hex(apiKey);
        String apiKeyPrefix = apiKey.substring(0, 12);

        String webhookSecret = "whsec_" + randomToken();

        // No "is this email taken?" check first. Two requests could both pass that check and
        // both insert. The UNIQUE constraint on email is the real guard: the database lets
        // exactly one insert through, and the other fails here.
        UUID merchantId;
        try {
            merchantId = merchants.insert(request.name().trim(), email, passwordHash,
                    apiKeyHash, apiKeyPrefix, webhookSecret);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "email_taken",
                    "A merchant with this email already exists");
        }
        ledgerService.openMerchantAccount(merchantId);

        return new RegisterResponse(merchantId, apiKey, webhookSecret);
    }

    public LoginResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        var credentials = merchants.findCredentialsByEmail(email);

        // Run BCrypt even when the email doesn't exist, so "unknown email" and "wrong password"
        // take the same time. Otherwise an attacker could time replies to discover which emails are registered.
        String hashToCheck = credentials.map(MerchantRepository.Credentials::passwordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);

        if (credentials.isEmpty() || !passwordMatches) {
            // Same message in both cases, for the same reason.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Email or password is incorrect");
        }

        String token = jwtService.issue(credentials.get().id());
        return new LoginResponse(token, jwtService.ttlSeconds());
    }

    public Merchant setWebhookUrl(UUID merchantId, String webhookUrl) {
        URI uri;
        try {
            uri = URI.create(webhookUrl.trim());
        } catch (IllegalArgumentException e) {
            uri = null;
        }
        if (uri == null || uri.getHost() == null
                || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_webhook_url",
                    "webhook_url must be an http(s) URL, e.g. https://shop.example.com/webhooks/payflow");
        }
        merchants.updateWebhookUrl(merchantId, uri.toString());
        return get(merchantId);
    }

    public Merchant get(UUID merchantId) {
        return merchants.findById(merchantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "Merchant not found"));
    }

    /** 32 cryptographically random bytes as URL-safe text (43 characters). */
    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
