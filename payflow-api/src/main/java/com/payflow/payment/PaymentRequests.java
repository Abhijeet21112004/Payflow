package com.payflow.payment;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** The JSON shapes coming in and going out of the payment endpoints. */
public final class PaymentRequests {

    private PaymentRequests() {
    }

    /** POST /payments. amount is in paise (₹1 = 100). */
    public record CreatePayment(
            @NotNull @Positive Long amount,
            @NotBlank String currency,
            @Email String customerEmail,
            @NotBlank @Pattern(regexp = "success|decline|timeout") String mockOutcome) {
    }

    /** POST /payments/{id}/refund. amount is optional: leave it out to refund everything that's left. */
    public record RefundPayment(@Positive Long amount) {
    }

    /** Reply to a refund: the refund itself, plus the payment as it is now. */
    public record RefundResponse(UUID id, UUID paymentId, long amount, Instant createdAt, Payment payment) {
        static RefundResponse of(Refund refund, Payment payment) {
            return new RefundResponse(refund.id(), refund.paymentId(), refund.amount(), refund.createdAt(), payment);
        }
    }
}
