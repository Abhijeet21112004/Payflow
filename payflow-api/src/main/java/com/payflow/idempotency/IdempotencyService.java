package com.payflow.idempotency;

import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.payflow.common.ApiException;
import com.payflow.common.Hashing;

import tools.jackson.databind.json.JsonMapper;

/**
 * Makes retries safe. Usage, inside ONE transaction with the real work:
 *
 *   replay = reserve(...)          → if present, return it: this exact request was already done
 *   ...do the work...
 *   return complete(..., result)   → stores the reply so future retries get the same one
 *
 * Because the reservation and the work commit together, there is no moment where the key says
 * "done" but the work isn't, or the work is done but the key doesn't know.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository keys;
    private final JsonMapper json;

    public IdempotencyService(IdempotencyRepository keys, JsonMapper json) {
        this.keys = keys;
        this.json = json;
    }

    public void requireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "missing_idempotency_key",
                    "Send an 'Idempotency-Key' header (e.g. a UUID) with every payment request");
        }
        if (key.length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_idempotency_key",
                    "Idempotency-Key must be at most 255 characters");
        }
    }

    /** Identifies the request: same method + path + body → same fingerprint. */
    public String fingerprint(String method, String path, Object body) {
        String bodyJson = body == null ? "" : json.writeValueAsString(body);
        return Hashing.sha256Hex(method + " " + path + "\n" + bodyJson);
    }

    /**
     * Empty → the key is now reserved for this request; go ahead and do the work.
     * Present → this request was already completed; send back the stored reply instead.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StoredResponse> reserve(UUID merchantId, String key, String fingerprint) {
        if (keys.tryReserve(merchantId, key, fingerprint)) {
            return Optional.empty();
        }

        IdempotencyRepository.KeyRow existing = keys.find(merchantId, key).orElseThrow();
        if (!existing.requestHash().equals(fingerprint)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency_key_reused",
                    "This Idempotency-Key was already used for a different request");
        }
        if ("in_progress".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "request_in_progress",
                    "A request with this Idempotency-Key is still being processed; retry shortly");
        }
        return Optional.of(new StoredResponse(existing.responseStatus(), existing.responseBody()));
    }

    /** Records the reply for this key and returns it, so the first response and every replay are identical. */
    @Transactional(propagation = Propagation.MANDATORY)
    public StoredResponse complete(UUID merchantId, String key, int status, Object body) {
        String bodyJson = json.writeValueAsString(body);
        keys.complete(merchantId, key, status, bodyJson);
        return new StoredResponse(status, bodyJson);
    }
}
