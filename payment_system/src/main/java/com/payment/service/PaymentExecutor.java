package com.payment.service;

import com.payment.model.PaymentOrder;

import java.util.UUID;

/**
 * Simulates the Payment Executor — communicates with PSP (Stripe, PayPal, etc.)
 * In production, this sends HTTP requests to the PSP's API.
 */
public class PaymentExecutor {

    /** Execute payment via PSP. Returns a PSP transaction ID on success. */
    public String execute(PaymentOrder order) {
        // Simulate PSP call
        System.out.printf("  [PSP] Processing $%.2f for order %s...%n",
                order.getAmount(), order.getPaymentOrderId());

        // Simulate: 90% success, 10% failure
        if (Math.random() < 0.9) {
            String pspTxnId = "psp_" + UUID.randomUUID().toString().substring(0, 8);
            System.out.println("  [PSP] Success! Transaction: " + pspTxnId);
            return pspTxnId;
        } else {
            System.out.println("  [PSP] FAILED — network timeout");
            return null;
        }
    }
}
