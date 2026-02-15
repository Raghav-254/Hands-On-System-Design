package com.digitalwallet.model;

public class WalletAccount {
    private final String accountId;
    private double balance;

    public WalletAccount(String accountId, double balance) {
        this.accountId = accountId;
        this.balance = balance;
    }

    public String getAccountId() { return accountId; }
    public double getBalance() { return balance; }
    public void setBalance(double balance) { this.balance = balance; }

    public void debit(double amount) {
        if (amount > balance) throw new IllegalStateException("Insufficient funds for " + accountId);
        this.balance -= amount;
    }

    public void credit(double amount) {
        this.balance += amount;
    }

    @Override
    public String toString() {
        return String.format("%s: $%.2f", accountId, balance);
    }
}
