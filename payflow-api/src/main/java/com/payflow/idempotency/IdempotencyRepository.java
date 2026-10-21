package com.payflow.idempotency;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    public record KeyRow(String requestHash, String status, Integer responseStatus, String responseBody) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Tries to claim the key. Returns true if this request claimed it, false if it already existed.
     *
     * If another open transaction has just inserted the same key, Postgres makes this INSERT WAIT
     * on the unique index until that transaction finishes. Then: if it committed, ON CONFLICT DO NOTHING
     * kicks in and we return false; if it rolled back, our insert goes through and we return true.
     */
    public boolean tryReserve(UUID merchantId, String key, String requestHash) {
        return !jdbc.queryForList("""
                INSERT INTO idempotency_keys (merchant_id, idempotency_key, request_hash, status)
                VALUES (?, ?, ?, 'in_progress')
                ON CONFLICT (merchant_id, idempotency_key) DO NOTHING
                RETURNING id
                """, UUID.class, merchantId, key, requestHash).isEmpty();
    }

    public Optional<KeyRow> find(UUID merchantId, String key) {
        return jdbc.query("""
                SELECT request_hash, status, response_status, response_body
                FROM idempotency_keys WHERE merchant_id = ? AND idempotency_key = ?
                """,
                (rs, rowNum) -> new KeyRow(rs.getString("request_hash"), rs.getString("status"),
                        (Integer) rs.getObject("response_status"), rs.getString("response_body")),
                merchantId, key).stream().findFirst();
    }

    public void complete(UUID merchantId, String key, int responseStatus, String responseBody) {
        jdbc.update("""
                UPDATE idempotency_keys
                SET status = 'completed', response_status = ?, response_body = ?, completed_at = now()
                WHERE merchant_id = ? AND idempotency_key = ?
                """, responseStatus, responseBody, merchantId, key);
    }
}
