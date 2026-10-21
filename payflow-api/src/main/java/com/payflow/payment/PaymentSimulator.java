package com.payflow.payment;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stands in for the card network (Visa, UPI, …). The caller picks the outcome with mock_outcome.
 * In real life this would be a slow network call, which is why PaymentService never holds a
 * database transaction open while calling it.
 */
@Component
public class PaymentSimulator {

    public record Result(String status, String failureReason) {
    }

    private final Duration timeoutDelay;

    public PaymentSimulator(@Value("${payflow.simulator.timeout-delay:3s}") Duration timeoutDelay) {
        this.timeoutDelay = timeoutDelay;
    }

    public Result authorize(String mockOutcome) {
        return switch (mockOutcome) {
            case "success" -> new Result(PaymentStatus.AUTHORIZED, null);
            case "decline" -> new Result(PaymentStatus.FAILED, "card_declined");
            case "timeout" -> {
                sleep(timeoutDelay);   // the "network" hangs, then we give up
                yield new Result(PaymentStatus.FAILED, "processor_timeout");
            }
            default -> throw new IllegalArgumentException("Unknown mock_outcome " + mockOutcome);
        };
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
