package com.digitalwallet.model;

public class TransferEvent {
    private final String eventId;
    private final int sequence;
    private final String account;
    private final double amount;
    private final String commandId;
    private final long timestamp;

    public TransferEvent(String eventId, int sequence, String account, double amount, String commandId) {
        this.eventId = eventId;
        this.sequence = sequence;
        this.account = account;
        this.amount = amount;
        this.commandId = commandId;
        this.timestamp = System.currentTimeMillis();
    }

    public String getEventId() { return eventId; }
    public int getSequence() { return sequence; }
    public String getAccount() { return account; }
    public double getAmount() { return amount; }
    public String getCommandId() { return commandId; }
    public long getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return String.format("Event[seq=%d]: %s %+.2f (cmd: %s)", sequence, account, amount, commandId);
    }
}
