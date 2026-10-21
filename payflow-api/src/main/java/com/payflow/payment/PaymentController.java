package com.payflow.payment;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.payflow.auth.JwtAuthFilter;
import com.payflow.payment.PaymentRequests.CreatePayment;
import com.payflow.payment.PaymentRequests.RefundPayment;
import com.payflow.payment.PaymentRequests.RefundResponse;

import jakarta.validation.Valid;

/** Payment endpoints, used by the shop's server. Protected by the API-key guard (/payments/**). */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<Payment> create(@RequestAttribute(JwtAuthFilter.MERCHANT_ID) UUID merchantId,
                                          @Valid @RequestBody CreatePayment request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentService.create(merchantId, request));
    }

    @PostMapping("/{id}/capture")
    public Payment capture(@RequestAttribute(JwtAuthFilter.MERCHANT_ID) UUID merchantId,
                           @PathVariable UUID id) {
        return paymentService.capture(merchantId, id);
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<RefundResponse> refund(@RequestAttribute(JwtAuthFilter.MERCHANT_ID) UUID merchantId,
                                                 @PathVariable UUID id,
                                                 @Valid @RequestBody(required = false) RefundPayment request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentService.refund(merchantId, id, request));
    }

    @GetMapping("/{id}")
    public Payment get(@RequestAttribute(JwtAuthFilter.MERCHANT_ID) UUID merchantId, @PathVariable UUID id) {
        return paymentService.get(merchantId, id);
    }
}
