package com.payflow.payment;

/**
 * The payment state machine:
 *
 *   created ──success──▶ authorized ──capture──▶ captured ──partial refund──▶ partially_refunded
 *      │                                             │                              │
 *      ├──decline──▶ failed                          └──full refund──▶ refunded ◀───┘ refund the rest
 *      └──timeout──▶ failed
 */
public final class PaymentStatus {

    public static final String CREATED = "created";
    public static final String AUTHORIZED = "authorized";
    public static final String CAPTURED = "captured";
    public static final String PARTIALLY_REFUNDED = "partially_refunded";
    public static final String REFUNDED = "refunded";
    public static final String FAILED = "failed";

    private PaymentStatus() {
    }

    public static boolean isRefundable(String status) {
        return CAPTURED.equals(status) || PARTIALLY_REFUNDED.equals(status);
    }
}
