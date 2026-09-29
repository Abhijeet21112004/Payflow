package com.payflow.merchant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordHashingTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void hashIsOneWayAndSalted() {
        String hash1 = encoder.encode("mango123");
        String hash2 = encoder.encode("mango123");

        System.out.println("hash 1: " + hash1);
        System.out.println("hash 2: " + hash2);

        // Same password, different hashes: each one has its own random salt.
        assertNotEquals(hash1, hash2);

        // Login check: hash what was typed and compare. Both stored hashes accept the right password...
        assertTrue(encoder.matches("mango123", hash1));
        assertTrue(encoder.matches("mango123", hash2));

        // ...and reject a wrong one.
        assertFalse(encoder.matches("mango124", hash1));
    }
}
