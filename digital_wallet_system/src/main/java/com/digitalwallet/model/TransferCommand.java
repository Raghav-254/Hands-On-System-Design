package com.digitalwallet.model;

public class TransferCommand {
    private final String commandId;
    private final String fromAccount;
    private final String toAccount;
    private final double amount;
    private final long timestamp;

    public TransferCommand(String commandId, String fromAccount, String toAccount, double amount) {
        this.commandId = commandId;
        this.fromAccount = fromAccount;
        this.toAccount = toAccount;
        this.amount = amount;
        this.timestamp = System.currentTimeMillis();
    }

    public String getCommandId() { return commandId; }
    public String getFromAccount() { return fromAccount; }
    public String getToAccount() { return toAccount; }
    public double getAmount() { return amount; }
    public long getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return String.format("Command[%s]: %s → %s ($%.2f)", commandId, fromAccount, toAccount, amount);
    }
}
