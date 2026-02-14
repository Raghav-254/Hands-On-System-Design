package com.payment.model;

import java.time.Instant;

/**
 * A double-entry ledger row.
 * Every payment generates TWO entries: one debit, one credit.
 * Total debits must always equal total credits.
 */
public class LedgerEntry {
    public enum Type { DEBIT, CREDIT }

    private final String entryId;
    private final String paymentOrderId;
    private final String account;
    private final Type type;
    private final double amount;
    private final Instant timestamp;

    public LedgerEntry(String entryId, String paymentOrderId, String account,
                       Type type, double amount) {
        this.entryId = entryId;
        this.paymentOrderId = paymentOrderId;
        this.account = account;
        this.type = type;
        this.amount = amount;
        this.timestamp = Instant.now();
    }

    public String getEntryId() { return entryId; }
    public String getPaymentOrderId() { return paymentOrderId; }
    public String getAccount() { return account; }
    public Type getType() { return type; }
    public double getAmount() { return amount; }

    @Override
    public String toString() {
        return String.format("  %s  %-20s  %s $%.2f", entryId, account, type, amount);
    }
}
