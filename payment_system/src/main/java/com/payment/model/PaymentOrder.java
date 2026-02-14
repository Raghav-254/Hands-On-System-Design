package com.payment.model;

import java.util.UUID;

/**
 * Represents a payment order in the system.
 * Tracks the lifecycle: CREATED → EXECUTING → SUCCESS / FAILED
 */
public class PaymentOrder {
    public enum Status { CREATED, EXECUTING, SUCCESS, FAILED }

    private final String paymentOrderId;
    private final String buyerId;
    private final String sellerId;
    private final double amount;
    private final String currency;
    private final String idempotencyKey;
    private Status status;
    private String pspTransactionId;

    public PaymentOrder(String buyerId, String sellerId, double amount,
                        String currency, String idempotencyKey) {
        this.paymentOrderId = UUID.randomUUID().toString().substring(0, 8);
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.amount = amount;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.status = Status.CREATED;
    }

    public String getPaymentOrderId() { return paymentOrderId; }
    public String getBuyerId() { return buyerId; }
    public String getSellerId() { return sellerId; }
    public double getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getPspTransactionId() { return pspTransactionId; }
    public void setPspTransactionId(String id) { this.pspTransactionId = id; }

    @Override
    public String toString() {
        return String.format("PaymentOrder[%s] %s→%s $%.2f %s (psp:%s)",
                paymentOrderId, buyerId, sellerId, amount, status,
                pspTransactionId != null ? pspTransactionId : "pending");
    }
}
