package com.payment.service;

import com.payment.model.LedgerEntry;
import com.payment.model.PaymentOrder;
import com.payment.storage.LedgerDB;

/**
 * Records double-entry ledger entries for every successful payment.
 * Every transaction creates exactly TWO entries (debit + credit) that balance.
 */
public class LedgerService {

    private final LedgerDB ledgerDB;
    private int entryCounter = 0;

    public LedgerService(LedgerDB ledgerDB) {
        this.ledgerDB = ledgerDB;
    }

    /** Record a successful pay-in: debit buyer, credit seller */
    public void recordPayIn(PaymentOrder order) {
        String orderId = order.getPaymentOrderId();

        // Debit buyer's account (money leaves buyer)
        ledgerDB.append(new LedgerEntry(
                "L" + (++entryCounter), orderId,
                "buyer:" + order.getBuyerId(),
                LedgerEntry.Type.DEBIT, order.getAmount()));

        // Credit seller's account (money arrives at seller)
        ledgerDB.append(new LedgerEntry(
                "L" + (++entryCounter), orderId,
                "seller:" + order.getSellerId(),
                LedgerEntry.Type.CREDIT, order.getAmount()));
    }

    /** Verify the fundamental accounting equation: debits == credits */
    public boolean auditBalance() {
        return ledgerDB.verifyBalance();
    }
}
