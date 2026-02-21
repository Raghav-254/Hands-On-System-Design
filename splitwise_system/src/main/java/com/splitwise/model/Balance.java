package com.splitwise.model;

/**
 * Represents one row in the balances table:
 * creditorId is owed `amount` by debtorId in the given group.
 */
public class Balance {
    private final String groupId;
    private final String creditorId;
    private final String debtorId;
    private double amount;

    public Balance(String groupId, String creditorId, String debtorId) {
        this.groupId = groupId;
        this.creditorId = creditorId;
        this.debtorId = debtorId;
        this.amount = 0;
    }

    public String getGroupId() { return groupId; }
    public String getCreditorId() { return creditorId; }
    public String getDebtorId() { return debtorId; }
    public double getAmount() { return amount; }

    public void addAmount(double delta) { this.amount += delta; }
    public void subtractAmount(double delta) { this.amount -= delta; }

    @Override
    public String toString() {
        return String.format("%s owes %s $%.2f", debtorId, creditorId, amount);
    }
}
