package com.payflow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class for API tests: starts the real app on a random port with a throwaway Postgres,
 * and provides small helpers for sending real HTTP requests to it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ApiTestSupport {

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    /** A freshly registered merchant, with everything a test might need to act as them. */
    protected record TestMerchant(UUID id, String email, String apiKey, String webhookSecret, String token) {
    }

    protected TestMerchant registerMerchant() throws Exception {
        String email = uniqueEmail();
        var registered = body(post("/merchants/register",
                Map.of("name", "Test Shop", "email", email, "password", "mango12345")));
        var login = body(post("/merchants/login", Map.of("email", email, "password", "mango12345")));
        return new TestMerchant(
                UUID.fromString(registered.get("merchant_id").asString()),
                email,
                registered.get("api_key").asString(),
                registered.get("webhook_secret").asString(),
                login.get("token").asString());
    }

    // ---- merchant endpoints (no auth, or login token) ----

    protected HttpResponse<String> post(String path, Map<String, ?> body) throws Exception {
        return send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
    }

    protected HttpResponse<String> get(String path, String bearerToken) throws Exception {
        var builder = request(path);
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return send(builder.GET());
    }

    protected HttpResponse<String> put(String path, String bearerToken, Map<String, ?> body) throws Exception {
        return send(request(path)
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
    }

    // ---- payment endpoints (API key + Idempotency-Key) ----

    /** POST with an API key. {@code body} may be null (no body); {@code idempotencyKey} may be null (header omitted). */
    protected HttpResponse<String> apiPost(String path, String apiKey, String idempotencyKey, Map<String, ?> body)
            throws Exception {
        var builder = request(path).header("Authorization", "Bearer " + apiKey);
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (body == null) {
            return send(builder.POST(HttpRequest.BodyPublishers.noBody()));
        }
        return send(builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
    }

    protected HttpResponse<String> apiGet(String path, String apiKey) throws Exception {
        return send(request(path).header("Authorization", "Bearer " + apiKey).GET());
    }

    /** Creates a payment with a fresh idempotency key and returns its id. */
    protected UUID createPayment(TestMerchant merchant, long amount, String mockOutcome) throws Exception {
        var response = apiPost("/payments", merchant.apiKey(), newKey(),
                Map.of("amount", amount, "currency", "INR", "customer_email", "buyer@example.com",
                        "mock_outcome", mockOutcome));
        return UUID.fromString(body(response).get("id").asString());
    }

    protected UUID createCapturedPayment(TestMerchant merchant, long amount) throws Exception {
        UUID paymentId = createPayment(merchant, amount, "success");
        apiPost("/payments/" + paymentId + "/capture", merchant.apiKey(), newKey(), null);
        return paymentId;
    }

    // ---- helpers ----

    protected long balanceOf(UUID merchantId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN e.direction = 'credit' THEN e.amount ELSE -e.amount END), 0)
                FROM ledger_entries e JOIN accounts a ON a.id = e.account_id
                WHERE a.merchant_id = ?
                """, Long.class, merchantId);
    }

    protected JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    protected String errorCode(HttpResponse<String> response) {
        return body(response).get("error").get("code").asString();
    }

    protected static String uniqueEmail() {
        return "shop-" + UUID.randomUUID() + "@example.com";
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
