package com.payflow.payment;

import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.payflow.common.ApiException;
import com.payflow.idempotency.IdempotencyService;
import com.payflow.idempotency.StoredResponse;
import com.payflow.ledger.LedgerService;
import com.payflow.payment.PaymentRequests.CreatePayment;
import com.payflow.payment.PaymentRequests.RefundPayment;
import com.payflow.payment.PaymentRequests.RefundResponse;
import com.payflow.webhook.WebhookService;

/**
 * Create, capture and refund. Database transactions are opened explicitly with {@code tx.execute(...)},
 * so you can see exactly where each one starts and ends.
 *
 * Every method that moves money:
 *   1. reserves the Idempotency-Key (or returns the stored reply if this request was already done),
 *   2. does the work: payment status, ledger entries, webhook event in the outbox,
 *   3. stores the reply under the key,
 * all inside one transaction, so either all of it happens or none of it does.
 */
@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final LedgerService ledger;
    private final PaymentSimulator simulator;
    private final IdempotencyService idempotency;
    private final WebhookService webhooks;
    private final TransactionTemplate tx;

    public PaymentService(PaymentRepository payments, LedgerService ledger, PaymentSimulator simulator,
                          IdempotencyService idempotency, WebhookService webhooks,
                          PlatformTransactionManager txManager) {
        this.payments = payments;
        this.ledger = ledger;
        this.simulator = simulator;
        this.idempotency = idempotency;
        this.webhooks = webhooks;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Result of the first transaction of create(): either a replay, or the new 'created' payment. */
    private record Started(Optional<StoredResponse> replay, Payment payment) {
    }

    public StoredResponse create(UUID merchantId, String idempotencyKey, CreatePayment request) {
        idempotency.requireValidKey(idempotencyKey);
        if (!"INR".equals(request.currency())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "unsupported_currency", "Only INR is supported");
        }
        String fingerprint = idempotency.fingerprint("POST", "/payments", request);

        // Transaction 1: reserve the key and record the payment as 'created'.
        Started started = tx.execute(status -> {
            Optional<StoredResponse> replay = idempotency.reserve(merchantId, idempotencyKey, fingerprint);
            if (replay.isPresent()) {
                return new Started(replay, null);
            }
            return new Started(Optional.empty(), payments.insertCreated(merchantId, request.amount(),
                    request.currency(), request.customerEmail(), request.mockOutcome()));
        });
        if (started.replay().isPresent()) {
            return started.replay().get();
        }

        // Ask the "card network". No transaction is open here: it may be slow, and holding a
        // transaction (and its database connection) open across a network call would starve everyone else.
        PaymentSimulator.Result result = simulator.authorize(request.mockOutcome());

        // Transaction 2: record the outcome, queue a webhook if it failed, and store the reply under the key.
        return tx.execute(status -> {
            Payment finished = payments.updateStatus(started.payment().id(), result.status(), result.failureReason());
            if (PaymentStatus.FAILED.equals(finished.status())) {
                webhooks.enqueue(WebhookService.PAYMENT_FAILED, finished, null);
            }
            return idempotency.complete(merchantId, idempotencyKey, 201, finished);
        });
    }

    public StoredResponse capture(UUID merchantId, String idempotencyKey, UUID paymentId) {
        idempotency.requireValidKey(idempotencyKey);
        String fingerprint = idempotency.fingerprint("POST", "/payments/" + paymentId + "/capture", null);

        return tx.execute(status -> {
            Optional<StoredResponse> replay = idempotency.reserve(merchantId, idempotencyKey, fingerprint);
            if (replay.isPresent()) {
                return replay.get();
            }

            // FOR UPDATE: parallel captures of this payment queue up here, one at a time.
            Payment payment = payments.findForUpdate(paymentId, merchantId).orElseThrow(PaymentService::notFound);

            if (!PaymentStatus.AUTHORIZED.equals(payment.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "invalid_state",
                        "Payment is '" + payment.status() + "', only 'authorized' payments can be captured");
            }

            ledger.postCapture(merchantId, payment.id(), payment.amount());
            Payment captured = payments.updateStatus(payment.id(), PaymentStatus.CAPTURED, null);
            webhooks.enqueue(WebhookService.PAYMENT_CAPTURED, captured, null);

            return idempotency.complete(merchantId, idempotencyKey, 200, captured);
        });
    }

    public StoredResponse refund(UUID merchantId, String idempotencyKey, UUID paymentId, RefundPayment request) {
        idempotency.requireValidKey(idempotencyKey);
        String fingerprint = idempotency.fingerprint("POST", "/payments/" + paymentId + "/refund", request);

        return tx.execute(status -> {
            Optional<StoredResponse> replay = idempotency.reserve(merchantId, idempotencyKey, fingerprint);
            if (replay.isPresent()) {
                return replay.get();
            }

            // FOR UPDATE: parallel refunds of this payment queue up here, so each one sees
            // the refunded_amount left by the one before it.
            Payment payment = payments.findForUpdate(paymentId, merchantId).orElseThrow(PaymentService::notFound);

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
            webhooks.enqueue(WebhookService.PAYMENT_REFUNDED, updated, refund.id());

            return idempotency.complete(merchantId, idempotencyKey, 201, RefundResponse.of(refund, updated));
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
