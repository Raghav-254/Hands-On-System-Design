package com.splitwise.event;

public class SplitwiseEvent {
    public enum Type { EXPENSE_ADDED, DEBT_SETTLED }

    private final Type type;
    private final String groupId;
    private final String userId;
    private final String expenseId;
    private final String settlementId;
    private final String message;

    public SplitwiseEvent(Type type, String groupId, String userId,
                          String expenseId, String settlementId, String message) {
        this.type = type;
        this.groupId = groupId;
        this.userId = userId;
        this.expenseId = expenseId;
        this.settlementId = settlementId;
        this.message = message;
    }

    public Type getType() { return type; }
    public String getGroupId() { return groupId; }
    public String getUserId() { return userId; }
    public String getExpenseId() { return expenseId; }
    public String getSettlementId() { return settlementId; }
    public String getMessage() { return message; }

    @Override
    public String toString() {
        return String.format("Event[%s group=%s: %s]", type, groupId, message);
    }
}
