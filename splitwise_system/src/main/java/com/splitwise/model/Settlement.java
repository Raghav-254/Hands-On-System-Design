package com.splitwise.model;

public class Settlement {
    private final String settlementId;
    private final String groupId;
    private final String fromUserId;
    private final String toUserId;
    private final double amount;

    public Settlement(String settlementId, String groupId,
                      String fromUserId, String toUserId, double amount) {
        this.settlementId = settlementId;
        this.groupId = groupId;
        this.fromUserId = fromUserId;
        this.toUserId = toUserId;
        this.amount = amount;
    }

    public String getSettlementId() { return settlementId; }
    public String getGroupId() { return groupId; }
    public String getFromUserId() { return fromUserId; }
    public String getToUserId() { return toUserId; }
    public double getAmount() { return amount; }

    @Override
    public String toString() {
        return String.format("Settlement[%s %s → %s $%.2f]",
            settlementId, fromUserId, toUserId, amount);
    }
}
