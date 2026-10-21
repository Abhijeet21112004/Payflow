package com.samplemerchant.webhook;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final SignatureVerifier verifier;
    private final EventProcessor processor;
    private final JsonMapper json;

    public WebhookController(SignatureVerifier verifier, EventProcessor processor, JsonMapper json) {
        this.verifier = verifier;
        this.processor = processor;
        this.json = json;
    }

    /** The body is taken as a raw String so the signature can be checked against the exact bytes PayFlow signed. */
    @PostMapping("/webhooks/payflow")
    public ResponseEntity<Map<String, String>> receive(
            @RequestHeader(value = "X-PayFlow-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-PayFlow-Signature", required = false) String signature,
            @RequestBody String rawBody) {

        if (!verifier.isValid(timestamp, signature, rawBody)) {
            return ResponseEntity.status(401).body(Map.of("status", "invalid_signature"));
        }

        JsonNode event = json.readTree(rawBody);
        UUID eventId = UUID.fromString(event.get("id").asString());
        String type = event.get("type").asString();
        JsonNode data = event.get("data");

        boolean processed = processor.processOnce(eventId, type,
                UUID.fromString(data.get("payment_id").asString()), data.get("amount").asLong());

        log.info("{} {} {}", processed ? "Processed" : "Ignored duplicate", type, eventId);
        // 200 even for a duplicate: we have it, so PayFlow should stop retrying.
        return ResponseEntity.ok(Map.of("status", processed ? "processed" : "duplicate"));
    }
}
