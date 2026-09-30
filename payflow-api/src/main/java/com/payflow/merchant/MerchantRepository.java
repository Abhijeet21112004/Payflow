package com.payflow.merchant;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** All SQL for the merchants table lives here. */
@Repository
public class MerchantRepository {

    /** Just what login needs: who it is, and the hash to check the password against. */
    public record Credentials(UUID id, String passwordHash) {
    }

    private static final RowMapper<Merchant> MERCHANT = (rs, rowNum) -> new Merchant(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("email"),
            rs.getString("api_key_prefix"),
            rs.getString("webhook_url"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public MerchantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a merchant and returns its new id.
     * Throws DuplicateKeyException if the email is already taken (the UNIQUE constraint on email).
     */
    public UUID insert(String name, String email, String passwordHash,
                       String apiKeyHash, String apiKeyPrefix, String webhookSecret) {
        return jdbc.queryForObject("""
                INSERT INTO merchants (name, email, password_hash, api_key_hash, api_key_prefix, webhook_secret)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                UUID.class,
                name, email, passwordHash, apiKeyHash, apiKeyPrefix, webhookSecret);
    }

    public Optional<Credentials> findCredentialsByEmail(String email) {
        return jdbc.query("SELECT id, password_hash FROM merchants WHERE email = ?",
                (rs, rowNum) -> new Credentials(rs.getObject("id", UUID.class), rs.getString("password_hash")),
                email).stream().findFirst();
    }

    public Optional<Merchant> findById(UUID id) {
        return jdbc.query("SELECT * FROM merchants WHERE id = ?", MERCHANT, id).stream().findFirst();
    }
}
