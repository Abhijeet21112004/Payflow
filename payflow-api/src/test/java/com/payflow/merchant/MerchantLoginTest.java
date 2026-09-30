package com.payflow.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.payflow.ApiTestSupport;

class MerchantLoginTest extends ApiTestSupport {

    private String email;

    @BeforeEach
    void registerMerchant() throws Exception {
        email = uniqueEmail();
        post("/merchants/register", Map.of("name", "Chai Point", "email", email, "password", "mango12345"));
    }

    @Test
    void loginGivesATokenThatUnlocksMe() throws Exception {
        var login = post("/merchants/login", Map.of("email", email, "password", "mango12345"));
        assertEquals(200, login.statusCode());
        assertEquals(3600, body(login).get("expires_in").asInt());

        var me = get("/merchants/me", body(login).get("token").asString());

        assertEquals(200, me.statusCode());
        assertEquals(email, body(me).get("email").asString());
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameError() throws Exception {
        var wrongPassword = post("/merchants/login", Map.of("email", email, "password", "wrong-password"));
        var unknownEmail = post("/merchants/login", Map.of("email", uniqueEmail(), "password", "mango12345"));

        assertEquals(401, wrongPassword.statusCode());
        assertEquals(401, unknownEmail.statusCode());
        assertEquals(wrongPassword.body(), unknownEmail.body());
    }

    @Test
    void meWithoutTokenIsRejected() throws Exception {
        var me = get("/merchants/me", null);

        assertEquals(401, me.statusCode());
        assertEquals("missing_token", errorCode(me));
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        var login = post("/merchants/login", Map.of("email", email, "password", "mango12345"));
        String token = body(login).get("token").asString();

        // Change one character in the payload (the middle part). The signature no longer matches.
        String[] parts = token.split("\\.");
        char c = parts[1].charAt(5);
        parts[1] = parts[1].substring(0, 5) + (c == 'A' ? 'B' : 'A') + parts[1].substring(6);
        String tampered = String.join(".", parts);

        var me = get("/merchants/me", tampered);

        assertEquals(401, me.statusCode());
        assertEquals("invalid_token", errorCode(me));
    }
}
