package com.payflow.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** Creates and checks login tokens. The token's payload carries only the merchant id and expiry. */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${payflow.jwt.secret}") String secret,
                      @Value("${payflow.jwt.ttl-minutes}") long ttlMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public String issue(UUID merchantId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(merchantId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)                       // HMAC-SHA256 with our secret
                .compact();
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }

    /** The merchant id inside the token, or empty if the token is tampered with, expired, or malformed. */
    public Optional<UUID> verify(String token) {
        try {
            String subject = Jwts.parser()
                    .verifyWith(key)                 // recomputes the signature and compares
                    .build()
                    .parseSignedClaims(token)        // also rejects expired tokens
                    .getPayload()
                    .getSubject();
            return Optional.of(UUID.fromString(subject));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
