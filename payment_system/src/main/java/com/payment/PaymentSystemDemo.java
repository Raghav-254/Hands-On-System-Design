package com.payment;

import com.payment.model.LedgerEntry;
import com.payment.model.PaymentOrder;
import com.payment.model.PayoutOrder;
import com.payment.service.LedgerService;
import com.payment.service.PaymentExecutor;
import com.payment.service.PaymentService;
import com.payment.storage.LedgerDB;
import com.payment.storage.PaymentDB;

/**
 * End-to-end demo of the Payment System.
 *
 * Demonstrates:
 *   1. Pay-in flow (customer → seller via PSP)
 *   2. Idempotency (duplicate request returns same result)
 *   3. Double-entry ledger (debit buyer, credit seller)
 *   4. Pay-out flow (platform → seller)
 *   5. Ledger audit (debits == credits)
 */
public class PaymentSystemDemo {

    public static void main(String[] args) {
        // --- Bootstrap ---
        PaymentDB paymentDB = new PaymentDB();
        LedgerDB ledgerDB = new LedgerDB();
        PaymentExecutor executor = new PaymentExecutor();
        LedgerService ledgerService = new LedgerService(ledgerDB);
        PaymentService paymentService = new PaymentService(paymentDB, executor, ledgerService);

        System.out.println("=== Payment System Demo ===\n");

        // --- 1. Pay-in: Customer buys a product ---
        System.out.println("--- Pay-in: Alice buys from SellerBob ($99.99) ---");
        PaymentOrder order1 = paymentService.processPayIn(
                "alice", "bob", 99.99, "USD", "idem-key-001");
        System.out.println("Result: " + order1 + "\n");

        // --- 2. Idempotency: Same request again (network retry) ---
        System.out.println("--- Idempotency: Same request again (idem-key-001) ---");
        PaymentOrder order1Dup = paymentService.processPayIn(
                "alice", "bob", 99.99, "USD", "idem-key-001");
        System.out.println("Result: " + order1Dup);
        System.out.println("Same object? " + (order1 == order1Dup) + "\n");

        // --- 3. Another payment ---
        System.out.println("--- Pay-in: Charlie buys from SellerDiana ($250.00) ---");
        PaymentOrder order2 = paymentService.processPayIn(
                "charlie", "diana", 250.00, "USD", "idem-key-002");
        System.out.println("Result: " + order2 + "\n");

        // --- 4. Double-entry ledger ---
        System.out.println("--- Ledger Entries ---");
        for (LedgerEntry entry : ledgerDB.allEntries()) {
            System.out.println(entry);
        }
        System.out.println();

        // --- 5. Audit: debits == credits ---
        System.out.println("--- Ledger Audit ---");
        boolean balanced = ledgerService.auditBalance();
        System.out.println("Debits == Credits? " + balanced + "\n");

        // --- 6. Pay-out: Platform pays seller ---
        System.out.println("--- Pay-out: Pay SellerBob $99.99 ---");
        PayoutOrder payout = paymentService.processPayOut("bob", 99.99, "USD");
        System.out.println("Result: " + payout + "\n");

        System.out.println("=== Demo complete ===");
    }
}
