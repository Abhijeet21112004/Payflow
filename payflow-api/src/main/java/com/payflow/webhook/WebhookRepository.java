package com.payflow.webhook;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookRepository {

    /** An event this dispatcher has claimed, plus where to send it and the secret to sign it with. */
    public record ClaimedEvent(UUID id, String payload, int attemptCount, String webhookUrl, String webhookSecret) {
    }

    private final JdbcTemplate jdbc;

    public WebhookRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID eventId, UUID merchantId, UUID paymentId, String eventType, String payloadJson) {
        jdbc.update("""
                INSERT INTO webhook_events (id, merchant_id, payment_id, event_type, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, eventId, merchantId, paymentId, eventType, payloadJson);
    }

    /**
     * Claims up to {@code limit} due events by putting a lease on them, in one statement.
     *
     * FOR UPDATE SKIP LOCKED: if several dispatchers run at once (several app instances), each one
     * skips rows another is claiming right now instead of waiting, so no event is sent twice at the same time.
     * The lease (locked_until) means that if this dispatcher crashes mid-send, the event becomes due again
     * once the lease runs out, instead of being stuck forever.
     */
    public List<ClaimedEvent> claimDue(int limit, int leaseSeconds) {
        return jdbc.query("""
                WITH claimed AS (
                  UPDATE webhook_events SET locked_until = now() + make_interval(secs => ?)
                  WHERE id IN (
                    SELECT id FROM webhook_events
                    WHERE status = 'pending'
                      AND next_attempt_at <= now()
                      AND (locked_until IS NULL OR locked_until < now())
                    ORDER BY next_attempt_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                  )
                  RETURNING id, merchant_id, payload, attempt_count
                )
                SELECT c.id, c.payload::text AS payload, c.attempt_count, m.webhook_url, m.webhook_secret
                FROM claimed c JOIN merchants m ON m.id = c.merchant_id
                """,
                (rs, rowNum) -> new ClaimedEvent(rs.getObject("id", UUID.class), rs.getString("payload"),
                        rs.getInt("attempt_count"), rs.getString("webhook_url"), rs.getString("webhook_secret")),
                leaseSeconds, limit);
    }

    public void markDelivered(UUID eventId, int attemptCount, int responseStatus) {
        jdbc.update("""
                UPDATE webhook_events
                SET status = 'delivered', attempt_count = ?, last_response_status = ?, last_error = NULL,
                    delivered_at = now(), locked_until = NULL
                WHERE id = ?
                """, attemptCount, responseStatus, eventId);
    }

    /** Records a failed attempt. {@code nextAttemptAt == null} means give up: the event is marked 'failed'. */
    public void markAttemptFailed(UUID eventId, int attemptCount, Instant nextAttemptAt,
                                  String error, Integer responseStatus) {
        jdbc.update("""
                UPDATE webhook_events
                SET status = ?, attempt_count = ?, next_attempt_at = COALESCE(?, next_attempt_at),
                    last_error = ?, last_response_status = ?, locked_until = NULL
                WHERE id = ?
                """,
                nextAttemptAt == null ? "failed" : "pending",
                attemptCount,
                nextAttemptAt == null ? null : OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC),
                error, responseStatus, eventId);
    }
}
