package com.payflow.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.payflow.ApiTestSupport;
import com.payflow.common.Hashing;

class MerchantRegistrationTest extends ApiTestSupport {

    @Test
    void registerReturnsKeysAndStoresOnlyHashes() throws Exception {
        String email = uniqueEmail();

        var response = post("/merchants/register",
                Map.of("name", "Chai Point", "email", email, "password", "mango12345"));

        assertEquals(201, response.statusCode());
        String apiKey = body(response).get("api_key").asString();
        assertTrue(apiKey.startsWith("pf_live_"));
        assertTrue(body(response).get("webhook_secret").asString().startsWith("whsec_"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM merchants WHERE email = ?", email);

        // The raw API key is never stored, only its SHA-256.
        assertEquals(Hashing.sha256Hex(apiKey), row.get("api_key_hash"));
        assertEquals(apiKey.substring(0, 12), row.get("api_key_prefix"));

        // The password is stored as a BCrypt hash that matches the original.
        String passwordHash = (String) row.get("password_hash");
        assertNotEquals("mango12345", passwordHash);
        assertTrue(new BCryptPasswordEncoder().matches("mango12345", passwordHash));
    }

    @Test
    void duplicateEmailIsRejectedEvenWithDifferentCase() throws Exception {
        String email = uniqueEmail();
        post("/merchants/register", Map.of("name", "A", "email", email, "password", "mango12345"));

        var response = post("/merchants/register",
                Map.of("name", "B", "email", email.toUpperCase(), "password", "mango12345"));

        assertEquals(409, response.statusCode());
        assertEquals("email_taken", errorCode(response));
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        var response = post("/merchants/register",
                Map.of("name", "Chai Point", "email", uniqueEmail(), "password", "short"));

        assertEquals(400, response.statusCode());
        assertEquals("invalid_request", errorCode(response));
    }
}
