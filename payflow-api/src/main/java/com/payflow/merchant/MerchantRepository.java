package com.payflow.merchant;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** All SQL for the merchants table lives here. */
@Repository
public class MerchantRepository {

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
}
