package com.payflow.merchant;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.payflow.common.ApiException;
import com.payflow.common.Hashing;

/** The registration logic: validate → hash → generate keys → save. */
@Service
public class MerchantService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MerchantRepository merchants;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public MerchantService(MerchantRepository merchants) {
        this.merchants = merchants;
    }

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

        return new RegisterResponse(merchantId, apiKey, webhookSecret);
    }

    /** 32 cryptographically random bytes as URL-safe text (43 characters). */
    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
