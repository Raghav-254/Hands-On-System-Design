package com.payment.storage;

import com.payment.model.PaymentOrder;
import com.payment.model.PayoutOrder;

import java.util.*;

/**
 * Simulates the payment database.
 * Stores payment orders, payout orders, and tracks idempotency keys.
 */
public class PaymentDB {

    private final Map<String, PaymentOrder> paymentOrders = new LinkedHashMap<>();
    private final Map<String, PayoutOrder> payoutOrders = new LinkedHashMap<>();
    private final Map<String, String> idempotencyIndex = new HashMap<>(); // key → paymentOrderId

    public void savePaymentOrder(PaymentOrder order) {
        paymentOrders.put(order.getPaymentOrderId(), order);
        idempotencyIndex.put(order.getIdempotencyKey(), order.getPaymentOrderId());
    }

    public PaymentOrder getPaymentOrder(String orderId) {
        return paymentOrders.get(orderId);
    }

    /** Check if this idempotency key has been seen before */
    public PaymentOrder findByIdempotencyKey(String key) {
        String orderId = idempotencyIndex.get(key);
        return orderId != null ? paymentOrders.get(orderId) : null;
    }

    public void savePayoutOrder(PayoutOrder order) {
        payoutOrders.put(order.getPayoutId(), order);
    }

    public Collection<PaymentOrder> allPaymentOrders() {
        return paymentOrders.values();
    }

    public Collection<PayoutOrder> allPayoutOrders() {
        return payoutOrders.values();
    }
}
