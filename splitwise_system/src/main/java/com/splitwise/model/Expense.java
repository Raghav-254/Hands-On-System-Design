package com.splitwise.model;

import java.util.*;

public class Expense {
    public enum SplitType { EQUAL, EXACT, PERCENTAGE }

    private final String expenseId;
    private final String groupId;
    private final String payerId;
    private final double amount;
    private final String description;
    private final SplitType splitType;
    private final Map<String, Double> splits; // userId → share amount

    public Expense(String expenseId, String groupId, String payerId, double amount,
                   String description, SplitType splitType, Map<String, Double> splits) {
        this.expenseId = expenseId;
        this.groupId = groupId;
        this.payerId = payerId;
        this.amount = amount;
        this.description = description;
        this.splitType = splitType;
        this.splits = splits;
    }

    public String getExpenseId() { return expenseId; }
    public String getGroupId() { return groupId; }
    public String getPayerId() { return payerId; }
    public double getAmount() { return amount; }
    public String getDescription() { return description; }
    public SplitType getSplitType() { return splitType; }
    public Map<String, Double> getSplits() { return Collections.unmodifiableMap(splits); }

    @Override
    public String toString() {
        return String.format("Expense[%s %s paid $%.2f '%s' split=%s]",
            expenseId, payerId, amount, description, splitType);
    }
}
