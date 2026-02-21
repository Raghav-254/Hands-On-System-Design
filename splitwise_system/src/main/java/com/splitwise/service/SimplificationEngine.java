package com.splitwise.service;

import java.util.*;

/**
 * Greedy debt simplification algorithm.
 * Input: net balance per user. Output: minimal list of transactions.
 *
 * Algorithm:
 * 1. Separate into creditors (net > 0) and debtors (net < 0).
 * 2. Greedily match largest creditor with largest debtor.
 * 3. Transfer min(creditor_net, |debtor_net|).
 * 4. Repeat until all nets are zero.
 *
 * Produces at most N-1 transactions.
 */
public class SimplificationEngine {

    public static class Transaction {
        public final String from;
        public final String to;
        public final double amount;

        public Transaction(String from, String to, double amount) {
            this.from = from;
            this.to = to;
            this.amount = Math.round(amount * 100.0) / 100.0;
        }

        @Override
        public String toString() {
            return String.format("%s → %s $%.2f", from, to, amount);
        }
    }

    /**
     * Simplify debts given net balances.
     * @param netBalances map of userId → net amount (positive = creditor, negative = debtor)
     * @return minimal list of transactions
     */
    public static List<Transaction> simplify(Map<String, Double> netBalances) {
        // Separate into creditors and debtors
        PriorityQueue<Map.Entry<String, Double>> creditors = new PriorityQueue<>(
            (a, b) -> Double.compare(b.getValue(), a.getValue())); // max-heap
        PriorityQueue<Map.Entry<String, Double>> debtors = new PriorityQueue<>(
            Comparator.comparingDouble(Map.Entry::getValue)); // min-heap (most negative first)

        for (Map.Entry<String, Double> entry : netBalances.entrySet()) {
            double net = entry.getValue();
            if (net > 0.01) {
                creditors.add(new AbstractMap.SimpleEntry<>(entry.getKey(), net));
            } else if (net < -0.01) {
                debtors.add(new AbstractMap.SimpleEntry<>(entry.getKey(), net));
            }
        }

        List<Transaction> transactions = new ArrayList<>();

        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            Map.Entry<String, Double> creditor = creditors.poll();
            Map.Entry<String, Double> debtor = debtors.poll();

            double transferAmount = Math.min(creditor.getValue(), Math.abs(debtor.getValue()));
            transactions.add(new Transaction(debtor.getKey(), creditor.getKey(), transferAmount));

            double newCreditorNet = creditor.getValue() - transferAmount;
            double newDebtorNet = debtor.getValue() + transferAmount;

            if (newCreditorNet > 0.01) {
                creditors.add(new AbstractMap.SimpleEntry<>(creditor.getKey(), newCreditorNet));
            }
            if (newDebtorNet < -0.01) {
                debtors.add(new AbstractMap.SimpleEntry<>(debtor.getKey(), newDebtorNet));
            }
        }

        return transactions;
    }
}
