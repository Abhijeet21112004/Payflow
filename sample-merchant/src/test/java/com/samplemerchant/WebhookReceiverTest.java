package com.samplemerchant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Property P5 (receiver side): every event is processed exactly once, and only genuine webhooks are accepted. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shop.payflow.webhook-secret=" + WebhookReceiverTest.SECRET)
class WebhookReceiverTest {

    static final String SECRET = "whsec_test-secret";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void aRedeliveredEventIsProcessedOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        String body = event(eventId);

        var first = send(body, now(), sign(SECRET, now(), body));
        var second = send(body, now(), sign(SECRET, now(), body));

        assertEquals(200, first.statusCode());
        assertEquals(200, second.statusCode());
        assertEquals("{\"status\":\"processed\"}", first.body());
        assertEquals("{\"status\":\"duplicate\"}", second.body());
        assertEquals(1, fulfillments(eventId));
    }

    @Test
    void twentySimultaneousDeliveriesAreProcessedOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        String body = event(eventId);
        long ts = now();
        String signature = sign(SECRET, ts, body);

        CountDownLatch gate = new CountDownLatch(1);
        List<HttpResponse<String>> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return send(body, ts, signature);
                }));
            }
            gate.countDown();
            for (var future : futures) {
                responses.add(future.get());
            }
        }

        assertEquals(20, responses.stream().filter(r -> r.statusCode() == 200).count());
        assertEquals(1, responses.stream().filter(r -> r.body().contains("processed")).count());
        assertEquals(1, fulfillments(eventId));
    }

    @Test
    void aForgedSignatureIsRejected() throws Exception {
        UUID eventId = UUID.randomUUID();
        String body = event(eventId);

        var response = send(body, now(), sign("whsec_attackers-guess", now(), body));

        assertEquals(401, response.statusCode());
        assertEquals(0, fulfillments(eventId));
    }

    @Test
    void aTamperedBodyIsRejected() throws Exception {
        UUID eventId = UUID.randomUUID();
        String genuine = event(eventId);
        String signature = sign(SECRET, now(), genuine);

        var response = send(genuine.replace("50000", "99999999"), now(), signature);

        assertEquals(401, response.statusCode());
    }

    @Test
    void anOldWebhookIsRejectedEvenWithAValidSignature() throws Exception {
        UUID eventId = UUID.randomUUID();
        String body = event(eventId);
        long tenMinutesAgo = now() - 600;

        var response = send(body, tenMinutesAgo, sign(SECRET, tenMinutesAgo, body));

        assertEquals(401, response.statusCode());
    }

    private int fulfillments(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM fulfillment_log WHERE event_id = ?", Integer.class, eventId);
    }

    private static String event(UUID eventId) {
        return """
                {"id":"%s","type":"payment.captured","created_at":"2026-10-21T10:00:00Z",\
                "data":{"payment_id":"%s","amount":50000,"currency":"INR","status":"captured","refunded_amount":0,"refund_id":null}}\
                """.formatted(eventId, UUID.randomUUID());
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    /** Written independently of the app's verifier, the way PayFlow signs (see PayFlow's WebhookSigner). */
    private static String sign(String secret, long timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "v1=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private HttpResponse<String> send(String body, long timestamp, String signature) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/payflow"))
                .header("Content-Type", "application/json")
                .header("X-PayFlow-Timestamp", String.valueOf(timestamp))
                .header("X-PayFlow-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
