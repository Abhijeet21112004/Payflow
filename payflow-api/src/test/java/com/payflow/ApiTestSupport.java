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

    protected HttpResponse<String> post(String path, Map<String, ?> body) throws Exception {
        return send(request(path, null)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
    }

    protected HttpResponse<String> get(String path, String bearerToken) throws Exception {
        return send(request(path, bearerToken).GET());
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

    private HttpRequest.Builder request(String path, String bearerToken) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return builder;
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
