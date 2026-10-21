package com.payflow.payment;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.payflow.common.ApiException;
import com.payflow.ledger.LedgerService;
import com.payflow.payment.PaymentRequests.CreatePayment;
import com.payflow.payment.PaymentRequests.RefundPayment;
import com.payflow.payment.PaymentRequests.RefundResponse;

/**
 * Create, capture and refund. Database transactions are opened explicitly with {@code tx.execute(...)},
 * so you can see exactly where each one starts and ends.
 */
@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final LedgerService ledger;
    private final PaymentSimulator simulator;
    private final TransactionTemplate tx;

    public PaymentService(PaymentRepository payments, LedgerService ledger, PaymentSimulator simulator,
                          PlatformTransactionManager txManager) {
        this.payments = payments;
        this.ledger = ledger;
        this.simulator = simulator;
        this.tx = new TransactionTemplate(txManager);
    }

    public Payment create(UUID merchantId, CreatePayment request) {
        if (!"INR".equals(request.currency())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "unsupported_currency", "Only INR is supported");
        }

        // Transaction 1: record the payment as 'created'.
        Payment created = tx.execute(status -> payments.insertCreated(merchantId, request.amount(),
                request.currency(), request.customerEmail(), request.mockOutcome()));

        // Ask the "card network". No transaction is open here: it may be slow.
        PaymentSimulator.Result result = simulator.authorize(request.mockOutcome());

        // Transaction 2: record the outcome.
        return tx.execute(status -> payments.updateStatus(created.id(), result.status(), result.failureReason()));
    }

    public Payment capture(UUID merchantId, UUID paymentId) {
        return tx.execute(status -> {
            Payment payment = payments.find(paymentId, merchantId).orElseThrow(PaymentService::notFound);

            if (!PaymentStatus.AUTHORIZED.equals(payment.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "invalid_state",
                        "Payment is '" + payment.status() + "', only 'authorized' payments can be captured");
            }

            ledger.postCapture(merchantId, payment.id(), payment.amount());
            return payments.updateStatus(payment.id(), PaymentStatus.CAPTURED, null);
        });
    }

    public RefundResponse refund(UUID merchantId, UUID paymentId, RefundPayment request) {
        return tx.execute(status -> {
            Payment payment = payments.find(paymentId, merchantId).orElseThrow(PaymentService::notFound);

            if (!PaymentStatus.isRefundable(payment.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "invalid_state",
                        "Payment is '" + payment.status() + "', only captured payments can be refunded");
            }

            long remaining = payment.amount() - payment.refundedAmount();
            long amount = request == null || request.amount() == null ? remaining : request.amount();
            if (amount > remaining) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "refund_exceeds_remaining",
                        "Only " + remaining + " paise can still be refunded");
            }

            Refund refund = payments.insertRefund(payment.id(), merchantId, amount);
            ledger.postRefund(merchantId, refund.id(), amount);

            long refundedAmount = payment.refundedAmount() + amount;
            String newStatus = refundedAmount == payment.amount() ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
            Payment updated = payments.updateRefunded(payment.id(), refundedAmount, newStatus);

            return RefundResponse.of(refund, updated);
        });
    }

    public Payment get(UUID merchantId, UUID paymentId) {
        return payments.find(paymentId, merchantId).orElseThrow(PaymentService::notFound);
    }

    // Another merchant's payment looks exactly like a missing one, so ids can't be probed.
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "Payment not found");
    }
}
