package com.payflow.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs webhook bodies so the merchant can prove a webhook really came from PayFlow and wasn't altered.
 *
 *   X-PayFlow-Signature: v1=hex( HMAC-SHA256(webhook_secret, timestamp + "." + body) )
 *
 * The timestamp is inside the signed text, so an attacker can't take an old webhook and resend it later
 * with a fresh timestamp: changing the timestamp breaks the signature.
 */
public final class WebhookSigner {

    private WebhookSigner() {
    }

    public static String sign(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return "v1=" + HexFormat.of().formatHex(signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is always available in Java", e);
        }
    }
}
