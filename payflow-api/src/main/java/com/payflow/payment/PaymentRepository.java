package com.payflow.payment;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** All SQL for the payments and refunds tables. */
@Repository
public class PaymentRepository {

    private static final RowMapper<Payment> PAYMENT = (rs, rowNum) -> new Payment(
            rs.getObject("id", UUID.class),
            rs.getObject("merchant_id", UUID.class),
            rs.getLong("amount"),
            rs.getString("currency"),
            rs.getString("status"),
            rs.getLong("refunded_amount"),
            rs.getString("mock_outcome"),
            rs.getString("failure_reason"),
            rs.getString("customer_email"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private static final RowMapper<Refund> REFUND = (rs, rowNum) -> new Refund(
            rs.getObject("id", UUID.class),
            rs.getObject("payment_id", UUID.class),
            rs.getLong("amount"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Payment insertCreated(UUID merchantId, long amount, String currency,
                                 String customerEmail, String mockOutcome) {
        return jdbc.queryForObject("""
                INSERT INTO payments (merchant_id, amount, currency, status, mock_outcome, customer_email)
                VALUES (?, ?, ?, 'created', ?, ?)
                RETURNING *
                """, PAYMENT, merchantId, amount, currency, mockOutcome, customerEmail);
    }

    /** Reads a payment. Only finds it if it belongs to this merchant. */
    public Optional<Payment> find(UUID paymentId, UUID merchantId) {
        return jdbc.query("SELECT * FROM payments WHERE id = ? AND merchant_id = ?", PAYMENT, paymentId, merchantId)
                .stream().findFirst();
    }

    public Payment updateStatus(UUID paymentId, String status, String failureReason) {
        return jdbc.queryForObject("""
                UPDATE payments SET status = ?, failure_reason = ?, updated_at = now()
                WHERE id = ?
                RETURNING *
                """, PAYMENT, status, failureReason, paymentId);
    }

    public Payment updateRefunded(UUID paymentId, long refundedAmount, String status) {
        return jdbc.queryForObject("""
                UPDATE payments SET refunded_amount = ?, status = ?, updated_at = now()
                WHERE id = ?
                RETURNING *
                """, PAYMENT, refundedAmount, status, paymentId);
    }

    public Refund insertRefund(UUID paymentId, UUID merchantId, long amount) {
        return jdbc.queryForObject("""
                INSERT INTO refunds (payment_id, merchant_id, amount) VALUES (?, ?, ?)
                RETURNING *
                """, REFUND, paymentId, merchantId, amount);
    }
}
