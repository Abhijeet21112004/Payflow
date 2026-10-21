package com.samplemerchant.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Checks that a webhook really came from PayFlow and is fresh.
 *
 * PayFlow sends   X-PayFlow-Signature: v1=hex( HMAC-SHA256(secret, timestamp + "." + raw_body) )
 * We recompute the same value with our copy of the secret and compare.
 */
@Component
public class SignatureVerifier {

    private final String secret;
    private final long toleranceSeconds;

    public SignatureVerifier(@Value("${shop.payflow.webhook-secret}") String secret,
                             @Value("${shop.payflow.tolerance-seconds}") long toleranceSeconds) {
        this.secret = secret;
        this.toleranceSeconds = toleranceSeconds;
    }

    public boolean isValid(String timestampHeader, String signatureHeader, String rawBody) {
        if (secret.isBlank() || timestampHeader == null || signatureHeader == null) {
            return false;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            return false;
        }
        // Too old (or from the future): could be an attacker replaying a webhook they captured earlier.
        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > toleranceSeconds) {
            return false;
        }

        // Must be computed over the RAW body exactly as received. Parsing and re-serialising the JSON
        // could reorder keys or change spacing, and the signature would no longer match.
        String expected = "v1=" + hmacHex(timestamp + "." + rawBody);

        // Constant-time comparison: takes the same time whether the first or the last character differs,
        // so an attacker can't guess the signature one character at a time by measuring response times.
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));
    }

    private String hmacHex(String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is always available in Java", e);
        }
    }
}
