package com.samplemerchant.webhook;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processes each PayFlow event exactly once, even though PayFlow may deliver it more than once.
 *
 * The trick: recording "I've seen this event" and doing the work happen in ONE transaction.
 *   - If the event id is new, both commit together.
 *   - If it's a duplicate, the insert does nothing and we skip the work.
 *   - If we crash halfway, both roll back, and PayFlow's retry gets a clean second chance.
 * There is never a state where the event is marked processed but the work wasn't done, or the reverse.
 */
@Service
public class EventProcessor {

    private final JdbcTemplate jdbc;

    public EventProcessor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this call processed the event, false if it was a duplicate */
    @Transactional
    public boolean processOnce(UUID eventId, String eventType, UUID paymentId, long amount) {
        // Two deliveries of the same event arriving at the same instant: the second INSERT waits on the
        // primary key until the first commits, then does nothing. Exactly one of them gets a row back.
        int inserted = jdbc.update("""
                INSERT INTO processed_events (event_id, event_type) VALUES (?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, eventType);
        if (inserted == 0) {
            return false;
        }

        jdbc.update("INSERT INTO fulfillment_log (event_id, payment_id, action, amount) VALUES (?, ?, ?, ?)",
                eventId, paymentId, actionFor(eventType), amount);
        return true;
    }

    private static String actionFor(String eventType) {
        return switch (eventType) {
            case "payment.captured" -> "ship order";
            case "payment.refunded" -> "record refund";
            case "payment.failed" -> "cancel order";
            default -> "ignore";
        };
    }
}
