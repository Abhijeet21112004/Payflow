package com.payflow.payment;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.payflow.auth.JwtAuthFilter;
import com.payflow.idempotency.StoredResponse;
import com.payflow.payment.PaymentRequests.CreatePayment;
import com.payflow.payment.PaymentRequests.RefundPayment;

import jakarta.validation.Valid;

/** Payment endpoints, used by the shop's server. Protected by the API-key guard (/payments/**). */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestAttribute(JwtAuthFilter.MERCHANT_ID) UUID merchantId,
                                         @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
                                         @Valid @RequestBody CreatePayment request) {
        return send(paymentService.create(merchantId, key, request));
    }

    @PostMapping("/{id}/capture")
    public ResponseEntity<String> capture(@RequestAttribute(JwtAuthFilter.MERCHANT_ID) UUID merchantId,
                                          @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
                                          @PathVariable UUID id) {
        return send(paymentService.capture(merchantId, key, id));
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<String> refund(@RequestAttribute(JwtAuthFilter.MERCHANT_ID) UUID merchantId,
                                         @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
                                         @PathVariable UUID id,
                                         @Valid @RequestBody(required = false) RefundPayment request) {
        return send(paymentService.refund(merchantId, key, id, request));
    }

    @GetMapping("/{id}")
    public Payment get(@RequestAttribute(JwtAuthFilter.MERCHANT_ID) UUID merchantId, @PathVariable UUID id) {
        return paymentService.get(merchantId, id);
    }

    // Money-moving endpoints reply with the stored text, so a first reply and its replays are identical.
    private static ResponseEntity<String> send(StoredResponse response) {
        return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON).body(response.body());
    }
}
