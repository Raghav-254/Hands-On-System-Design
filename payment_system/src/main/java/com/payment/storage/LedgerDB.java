package com.payment.storage;

import com.payment.model.LedgerEntry;

import java.util.*;

/**
 * Simulates the double-entry ledger database.
 * Append-only: entries are never modified or deleted.
 */
public class LedgerDB {

    private final List<LedgerEntry> entries = new ArrayList<>();

    public void append(LedgerEntry entry) {
        entries.add(entry);
    }

    /** Get all entries for a given payment order */
    public List<LedgerEntry> getEntriesForOrder(String paymentOrderId) {
        List<LedgerEntry> result = new ArrayList<>();
        for (LedgerEntry e : entries) {
            if (e.getPaymentOrderId().equals(paymentOrderId)) {
                result.add(e);
            }
        }
        return result;
    }

    /** Verify double-entry invariant: total debits == total credits */
    public boolean verifyBalance() {
        double totalDebit = 0, totalCredit = 0;
        for (LedgerEntry e : entries) {
            if (e.getType() == LedgerEntry.Type.DEBIT) totalDebit += e.getAmount();
            else totalCredit += e.getAmount();
        }
        return Math.abs(totalDebit - totalCredit) < 0.01;
    }

    public List<LedgerEntry> allEntries() {
        return Collections.unmodifiableList(entries);
    }
}
