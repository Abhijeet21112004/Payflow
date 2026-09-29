package com.payflow.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.payflow.TestcontainersConfiguration;
import com.payflow.common.Hashing;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Starts the real app on a random port with a throwaway Postgres, and sends it real HTTP requests. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MerchantRegistrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void registerReturnsKeysAndStoresOnlyHashes() throws Exception {
        String email = uniqueEmail();

        var response = post("/merchants/register",
                Map.of("name", "Chai Point", "email", email, "password", "mango12345"));

        assertEquals(201, response.statusCode());
        JsonNode body = json.readTree(response.body());
        String apiKey = body.get("api_key").asString();
        assertTrue(apiKey.startsWith("pf_live_"));
        assertTrue(body.get("webhook_secret").asString().startsWith("whsec_"));

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
        assertEquals("email_taken", json.readTree(response.body()).get("error").get("code").asString());
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        var response = post("/merchants/register",
                Map.of("name", "Chai Point", "email", uniqueEmail(), "password", "short"));

        assertEquals(400, response.statusCode());
        assertEquals("invalid_request", json.readTree(response.body()).get("error").get("code").asString());
    }

    private HttpResponse<String> post(String path, Map<String, String> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String uniqueEmail() {
        return "shop-" + UUID.randomUUID() + "@example.com";
    }
}
