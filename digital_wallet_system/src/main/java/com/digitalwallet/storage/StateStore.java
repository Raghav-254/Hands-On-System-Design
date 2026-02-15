package com.digitalwallet.storage;

import com.digitalwallet.model.TransferEvent;
import com.digitalwallet.model.WalletAccount;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simulates the State Store (RocksDB in production).
 * Holds current account balances — derived from events.
 */
public class StateStore {
    private final Map<String, WalletAccount> accounts = new LinkedHashMap<>();
    private int lastAppliedSequence = 0;

    public void initAccount(String accountId, double balance) {
        accounts.put(accountId, new WalletAccount(accountId, balance));
    }

    public WalletAccount getAccount(String accountId) {
        return accounts.get(accountId);
    }

    public double getBalance(String accountId) {
        WalletAccount account = accounts.get(accountId);
        return account != null ? account.getBalance() : 0.0;
    }

    public void applyEvent(TransferEvent event) {
        WalletAccount account = accounts.get(event.getAccount());
        if (account == null) {
            account = new WalletAccount(event.getAccount(), 0);
            accounts.put(event.getAccount(), account);
        }
        if (event.getAmount() < 0) {
            account.debit(Math.abs(event.getAmount()));
        } else {
            account.credit(event.getAmount());
        }
        lastAppliedSequence = event.getSequence();
    }

    public int getLastAppliedSequence() {
        return lastAppliedSequence;
    }

    public Map<String, Double> getSnapshot() {
        Map<String, Double> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, WalletAccount> entry : accounts.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().getBalance());
        }
        return snapshot;
    }

    public void printState() {
        System.out.println("  Current State (last_event_seq=" + lastAppliedSequence + "):");
        for (WalletAccount account : accounts.values()) {
            System.out.println("    " + account);
        }
    }
}
