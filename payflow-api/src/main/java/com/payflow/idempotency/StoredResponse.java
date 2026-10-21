package com.payflow.idempotency;

/** A reply exactly as it was (or will be) sent: status code + JSON body text. */
public record StoredResponse(int status, String body) {
}
