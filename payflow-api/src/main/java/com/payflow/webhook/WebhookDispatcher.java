package com.payflow.webhook;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background loop that delivers webhook events. Delivery is AT-LEAST-ONCE: an event is retried until the
 * merchant replies 2xx, so a merchant may occasionally receive the same event twice (e.g. if they processed
 * it but their reply got lost). That's why every event carries an id the merchant can de-duplicate on.
 *
 * Retries back off: by default 5s, 30s, 2m, 10m, 1h after attempts 1..5; after the 6th failure we give up.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final int BATCH_SIZE = 10;
    private static final int LEASE_SECONDS = 30;

    private final WebhookRepository events;
    private final List<Duration> backoff;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public WebhookDispatcher(WebhookRepository events,
                             @Value("${payflow.webhooks.backoff:5s,30s,2m,10m,1h}") List<Duration> backoff) {
        this.events = events;
        this.backoff = backoff;
    }

    @Scheduled(fixedDelayString = "${payflow.webhooks.poll-interval-ms:1000}")
    public void deliverDueEvents() {
        for (WebhookRepository.ClaimedEvent event : events.claimDue(BATCH_SIZE, LEASE_SECONDS)) {
            deliver(event);
        }
    }

    private void deliver(WebhookRepository.ClaimedEvent event) {
        int attempt = event.attemptCount() + 1;

        if (event.webhookUrl() == null) {
            recordFailure(event, attempt, "Merchant has no webhook_url configured", null);
            return;
        }

        long timestamp = Instant.now().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(URI.create(event.webhookUrl()))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-PayFlow-Event-Id", event.id().toString())
                .header("X-PayFlow-Timestamp", String.valueOf(timestamp))
                .header("X-PayFlow-Signature", WebhookSigner.sign(event.webhookSecret(), timestamp, event.payload()))
                .POST(HttpRequest.BodyPublishers.ofString(event.payload()))
                .build();

        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                events.markDelivered(event.id(), attempt, response.statusCode());
            } else {
                recordFailure(event, attempt, "Merchant replied HTTP " + response.statusCode(), response.statusCode());
            }
        } catch (IOException | IllegalArgumentException e) {
            recordFailure(event, attempt, e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void recordFailure(WebhookRepository.ClaimedEvent event, int attempt, String error, Integer status) {
        boolean giveUp = attempt > backoff.size();
        Instant next = giveUp ? null : Instant.now().plus(backoff.get(attempt - 1));
        events.markAttemptFailed(event.id(), attempt, next, error, status);
        if (giveUp) {
            log.warn("Webhook {} failed after {} attempts; giving up. Last error: {}", event.id(), attempt, error);
        }
    }
}
