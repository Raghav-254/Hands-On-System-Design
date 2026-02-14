package com.payment.model;

import java.util.UUID;

/**
 * Represents a pay-out (sending money to a seller).
 */
public class PayoutOrder {
    public enum Status { PENDING, EXECUTING, SUCCESS, FAILED }

    private final String payoutId;
    private final String sellerId;
    private final double amount;
    private final String currency;
    private Status status;

    public PayoutOrder(String sellerId, double amount, String currency) {
        this.payoutId = "PO-" + UUID.randomUUID().toString().substring(0, 6);
        this.sellerId = sellerId;
        this.amount = amount;
        this.currency = currency;
        this.status = Status.PENDING;
    }

    public String getPayoutId() { return payoutId; }
    public String getSellerId() { return sellerId; }
    public double getAmount() { return amount; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    @Override
    public String toString() {
        return String.format("Payout[%s] → %s $%.2f %s", payoutId, sellerId, amount, status);
    }
}
