package com.payment.service;

import com.payment.model.PaymentOrder;
import com.payment.model.PayoutOrder;
import com.payment.storage.PaymentDB;

/**
 * Core payment service — orchestrates pay-in and pay-out flows.
 *
 * Pay-in:  Buyer → Payment Service → Payment Executor → PSP → Card Schemes
 * Pay-out: Payment Service → PSP → Seller's bank account
 */
public class PaymentService {

    private final PaymentDB paymentDB;
    private final PaymentExecutor executor;
    private final LedgerService ledgerService;

    public PaymentService(PaymentDB paymentDB, PaymentExecutor executor,
                          LedgerService ledgerService) {
        this.paymentDB = paymentDB;
        this.executor = executor;
        this.ledgerService = ledgerService;
    }

    /**
     * Process a pay-in (customer pays for an order).
     * Implements idempotency via idempotency key.
     */
    public PaymentOrder processPayIn(String buyerId, String sellerId,
                                      double amount, String currency,
                                      String idempotencyKey) {
        // 1. Idempotency check
        PaymentOrder existing = paymentDB.findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            System.out.println("  [IDEMPOTENT] Duplicate request — returning cached result");
            return existing;
        }

        // 2. Create payment order
        PaymentOrder order = new PaymentOrder(buyerId, sellerId, amount, currency, idempotencyKey);
        paymentDB.savePaymentOrder(order);
        System.out.println("  Created: " + order);

        // 3. Execute via PSP
        order.setStatus(PaymentOrder.Status.EXECUTING);
        String pspTxnId = executor.execute(order);

        if (pspTxnId != null) {
            // 4. Success — update status and record in ledger
            order.setPspTransactionId(pspTxnId);
            order.setStatus(PaymentOrder.Status.SUCCESS);

            // 5. Double-entry ledger
            ledgerService.recordPayIn(order);

            // 6. Update wallet (seller balance)
            System.out.println("  [WALLET] Seller " + sellerId + " balance += $" + amount);
        } else {
            // 4b. Failure
            order.setStatus(PaymentOrder.Status.FAILED);
            System.out.println("  [FAILED] Payment failed — eligible for retry");
        }

        return order;
    }

    /** Process a pay-out (send money to seller) */
    public PayoutOrder processPayOut(String sellerId, double amount, String currency) {
        PayoutOrder payout = new PayoutOrder(sellerId, amount, currency);
        paymentDB.savePayoutOrder(payout);

        payout.setStatus(PayoutOrder.Status.EXECUTING);
        System.out.println("  Processing payout: " + payout);

        // Simulate PSP payout (always succeeds in demo)
        payout.setStatus(PayoutOrder.Status.SUCCESS);
        System.out.println("  [PSP] Payout complete: " + payout);

        return payout;
    }
}
