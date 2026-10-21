package com.payflow.ledger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.payflow.common.ApiException;

/** A bookmark into the ledger history: "the last row you saw". Sent to clients as an opaque string. */
public record Cursor(Instant createdAt, UUID id) {

    public String encode() {
        String raw = createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|");
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_cursor", "Cursor is not valid");
        }
    }
}
